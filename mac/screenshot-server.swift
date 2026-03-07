import Foundation
import IOBluetooth
import ImageIO
import CoreMedia
import VideoToolbox
import CoreVideo

#if canImport(ScreenCaptureKit)
import ScreenCaptureKit
#endif

setbuf(stdout, nil)

// Screenshot quality settings
var maxDimension = 1280  // max width or height in pixels
var jpegQuality = 35     // 1-100, lower = smaller + faster
let wifiPort: UInt16 = 9877

let kServiceUUID = "A5D3E9F0-7B1C-4C2E-9F3A-B8C1D2E4F6A7"

// MARK: - ScreenCaptureKit (macOS 12.3+) with legacy fallback

let useScreenCaptureKit: Bool = {
    let v = ProcessInfo.processInfo.operatingSystemVersion
    return v.majorVersion >= 13 || (v.majorVersion == 12 && v.minorVersion >= 3)
}()

var sckCapture: AnyObject? = nil  // type-erased SCKCapture

#if canImport(ScreenCaptureKit)
@available(macOS 12.3, *)
class SCKCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private var stream: SCStream?
    private var latestBuffer: CVPixelBuffer?
    private let lock = NSLock()
    var running = false

    // ---- Push-model streaming state ----
    private var pushFd: Int32 = -1
    private var pushParams = CaptureParams()
    private var pushStop = false
    private var pushEncoding = false  // back-pressure: true = encode queue busy
    private var pushPrevBuffer: CVPixelBuffer?
    private var pushPrevCGImage: CGImage?
    private var pushKeyCounter = 0
    var pushFrameCount = 0
    private var pushStartTime: CFAbsoluteTime = 0
    private var pushDoneSemaphore: DispatchSemaphore?
    private let encodeQueue = DispatchQueue(label: "com.tabletpen.encode", qos: .userInitiated)

    func start() async throws {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else {
            throw NSError(domain: "SCK", code: 1, userInfo: [NSLocalizedDescriptionKey: "No display found"])
        }

        let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
        let config = SCStreamConfiguration()
        config.width = display.width
        config.height = display.height
        config.minimumFrameInterval = CMTime(value: 1, timescale: 30) // 30 FPS max
        config.queueDepth = 3
        config.showsCursor = true
        config.pixelFormat = kCVPixelFormatType_32BGRA

        stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream!.addStreamOutput(self, type: .screen, sampleHandlerQueue: .global(qos: .userInteractive))
        try await stream!.startCapture()
        running = true
    }

    func stop() async {
        running = false
        try? await stream?.stopCapture()
        stream = nil
    }

    func grabFrame() -> CVPixelBuffer? {
        lock.lock()
        let buf = latestBuffer
        lock.unlock()
        return buf
    }

    var previousFrame: CVPixelBuffer? {
        lock.lock()
        let buf = previousBuffer
        lock.unlock()
        return buf
    }

    private var previousBuffer: CVPixelBuffer?
    private var unchangedCount = 0

    // ---- Push-model streaming API ----

    /// Start push-model streaming. Returns a semaphore that signals when streaming ends.
    func startPushStream(fd: Int32, params: CaptureParams) -> DispatchSemaphore {
        let sem = DispatchSemaphore(value: 0)
        lock.lock()
        pushFd = fd
        pushParams = params
        pushStop = false
        pushEncoding = false
        pushPrevBuffer = nil
        pushPrevCGImage = nil
        pushKeyCounter = 0
        pushFrameCount = 0
        pushStartTime = CFAbsoluteTimeGetCurrent()
        pushDoneSemaphore = sem
        unchangedCount = 30  // force next callback to process (bypass adaptive skip)
        lock.unlock()

        print("  Push stream started (fd=\(fd))")

        // Start stop-reader thread: blocking read on fd for "stop" command
        DispatchQueue.global(qos: .utility).async { [weak self] in
            var buf = [UInt8](repeating: 0, count: 64)
            while true {
                let n = Darwin.read(fd, &buf, buf.count)
                if n <= 0 {
                    // Client disconnected
                    self?.stopPushStream()
                    return
                }
                if let msg = String(bytes: buf[0..<n], encoding: .utf8), msg.contains("stop") {
                    self?.stopPushStream()
                    return
                }
            }
        }

        return sem
    }

    /// Stop push-model streaming and signal the done semaphore.
    func stopPushStream() {
        lock.lock()
        let wasActive = pushFd >= 0
        pushFd = -1
        pushStop = true
        pushPrevBuffer = nil
        pushPrevCGImage = nil
        let sem = pushDoneSemaphore
        pushDoneSemaphore = nil
        lock.unlock()

        if wasActive {
            sem?.signal()
        }
    }

    var isPushStreaming: Bool {
        lock.lock()
        let active = pushFd >= 0 && !pushStop
        lock.unlock()
        return active
    }

    // ---- SCK callback: push frames during streaming ----

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .screen else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        lock.lock()
        let streamFd = pushFd
        let stopped = pushStop
        let streaming = streamFd >= 0 && !stopped
        let encoding = pushEncoding
        let params = pushParams
        lock.unlock()

        if !streaming {
            // Single-screenshot mode: adaptive FPS filter
            if let prev = previousBuffer, !hasPixelsChanged(prev, pixelBuffer) {
                unchangedCount += 1
                if unchangedCount < 30 { return }
                unchangedCount = 0
            } else {
                unchangedCount = 0
            }

            lock.lock()
            previousBuffer = latestBuffer
            latestBuffer = pixelBuffer
            lock.unlock()
            return
        }

        // Push-model streaming: dispatch to encode queue
        // Back-pressure: skip frame if previous is still encoding
        if encoding { return }

        lock.lock()
        previousBuffer = latestBuffer
        latestBuffer = pixelBuffer
        pushEncoding = true
        lock.unlock()

        // pixelBuffer is retained by Swift ARC via closure capture
        encodeQueue.async { [weak self, pixelBuffer] in
            defer {
                self?.lock.lock()
                self?.pushEncoding = false
                self?.lock.unlock()
            }
            self?.encodeAndSendFrame(pixelBuffer: pixelBuffer, fd: streamFd, params: params)
        }
    }

    /// Encode a frame and send it over the socket. Runs on the serial encode queue.
    private func encodeAndSendFrame(pixelBuffer: CVPixelBuffer, fd: Int32, params: CaptureParams) {
        lock.lock()
        let stopped = pushStop
        lock.unlock()
        if stopped { return }

        let t0 = CFAbsoluteTimeGetCurrent()

        // Convert CVPixelBuffer → CGImage
        var cgImage: CGImage?
        VTCreateCGImageFromCVPixelBuffer(pixelBuffer, options: nil, imageOut: &cgImage)
        guard var image = cgImage else { return }

        if let r = params.region {
            image = cropCGImage(image, region: r)
        }

        let t1 = CFAbsoluteTimeGetCurrent()

        // Delta logic
        lock.lock()
        let prevBuf = pushPrevBuffer
        let keyCounter = pushKeyCounter
        lock.unlock()

        let needsKey = keyCounter >= KEY_FRAME_INTERVAL || prevBuf == nil
        var sent = false

        if !needsKey, let prevBuf = prevBuf {
            let tiles = identifyChangedTiles(pixelBuffer, prevBuf)
            if tiles.isEmpty {
                sent = true // No change — skip
            } else {
                let totalTiles = (CVPixelBufferGetWidth(pixelBuffer) / TILE_SIZE + 1)
                    * (CVPixelBufferGetHeight(pixelBuffer) / TILE_SIZE + 1)
                if tiles.count <= totalTiles / 2 {
                    // Delta frame
                    let payload = encodeDeltaPayload(tiles, image: image, quality: params.quality)
                    if writeTypedFrame(type: 0x01, payload, to: fd) {
                        lock.lock()
                        pushKeyCounter += 1
                        pushFrameCount += 1
                        lock.unlock()
                        let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
                        print("  [push-delta] \(tiles.count) tiles \(payload.count/1024)KB \(ms)ms")
                        sent = true
                    } else {
                        stopPushStream()
                        return
                    }
                }
                // >50% changed: fall through to key frame
            }
        }

        if !sent {
            // Key frame: full JPEG
            guard let frame = encodeJPEG(image, quality: params.quality, maxDim: params.maxDim) else { return }
            let t2 = CFAbsoluteTimeGetCurrent()
            if writeTypedFrame(type: 0x02, frame.data, to: fd) {
                lock.lock()
                pushKeyCounter = 0
                pushFrameCount += 1
                let elapsed = CFAbsoluteTimeGetCurrent() - pushStartTime
                let fps = elapsed > 0 ? Double(pushFrameCount) / elapsed : 0
                lock.unlock()
                let convertMs = Int((t1 - t0) * 1000)
                let encodeMs = Int((t2 - t1) * 1000)
                print("  [push-key] \(frame.data.count/1024)KB convert:\(convertMs)ms encode:\(encodeMs)ms fps:\(String(format: "%.1f", fps))")
            } else {
                stopPushStream()
                return
            }
        }

        // Update previous frame for delta comparison
        lock.lock()
        pushPrevBuffer = pixelBuffer
        pushPrevCGImage = image
        lock.unlock()
    }

    /// Quick pixel diff: sample 16 random-ish positions
    private func hasPixelsChanged(_ a: CVPixelBuffer, _ b: CVPixelBuffer) -> Bool {
        let w = CVPixelBufferGetWidth(a)
        let h = CVPixelBufferGetHeight(a)
        guard w == CVPixelBufferGetWidth(b), h == CVPixelBufferGetHeight(b) else { return true }

        CVPixelBufferLockBaseAddress(a, .readOnly)
        CVPixelBufferLockBaseAddress(b, .readOnly)
        defer {
            CVPixelBufferUnlockBaseAddress(a, .readOnly)
            CVPixelBufferUnlockBaseAddress(b, .readOnly)
        }

        guard let ptrA = CVPixelBufferGetBaseAddress(a),
              let ptrB = CVPixelBufferGetBaseAddress(b) else { return true }

        let bytesPerRow = CVPixelBufferGetBytesPerRow(a)
        let threshold = 10  // per-channel difference threshold

        // Sample 16 positions (4x4 grid)
        for gy in 0..<4 {
            for gx in 0..<4 {
                let x = (w * (2 * gx + 1)) / 8
                let y = (h * (2 * gy + 1)) / 8
                let offset = y * bytesPerRow + x * 4  // BGRA = 4 bytes per pixel
                let bA = ptrA.load(fromByteOffset: offset, as: UInt8.self)
                let bB = ptrB.load(fromByteOffset: offset, as: UInt8.self)
                if abs(Int(bA) - Int(bB)) > threshold { return true }
            }
        }
        return false
    }

    // SCStreamDelegate — handle errors
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("SCK stream error: \(error.localizedDescription)")
        running = false
        stopPushStream()
    }
}
#endif

// MARK: - Delta compression: tile diff + selective encode

let TILE_SIZE = 64
let KEY_FRAME_INTERVAL = 30  // send full frame every 30 frames
let DELTA_CHANGE_THRESHOLD = 10  // per-channel pixel diff threshold

/// Identify 64x64 tiles that changed between two CVPixelBuffers
@available(macOS 12.3, *)
func identifyChangedTiles(_ a: CVPixelBuffer, _ b: CVPixelBuffer) -> [(x: Int, y: Int, w: Int, h: Int)] {
    let w = CVPixelBufferGetWidth(a)
    let h = CVPixelBufferGetHeight(a)
    guard w == CVPixelBufferGetWidth(b), h == CVPixelBufferGetHeight(b) else {
        return [(x: 0, y: 0, w: w, h: h)]  // size changed = full screen
    }

    CVPixelBufferLockBaseAddress(a, .readOnly)
    CVPixelBufferLockBaseAddress(b, .readOnly)
    defer {
        CVPixelBufferUnlockBaseAddress(a, .readOnly)
        CVPixelBufferUnlockBaseAddress(b, .readOnly)
    }

    guard let ptrA = CVPixelBufferGetBaseAddress(a),
          let ptrB = CVPixelBufferGetBaseAddress(b) else {
        return [(x: 0, y: 0, w: w, h: h)]
    }

    let bytesPerRow = CVPixelBufferGetBytesPerRow(a)
    var changed: [(x: Int, y: Int, w: Int, h: Int)] = []

    let cols = (w + TILE_SIZE - 1) / TILE_SIZE
    let rows = (h + TILE_SIZE - 1) / TILE_SIZE

    for row in 0..<rows {
        for col in 0..<cols {
            let tx = col * TILE_SIZE
            let ty = row * TILE_SIZE
            let tw = min(TILE_SIZE, w - tx)
            let th = min(TILE_SIZE, h - ty)

            // Sample 4 pixels per tile (corners)
            var tileChanged = false
            let samplePoints = [(tx, ty), (tx + tw - 1, ty), (tx, ty + th - 1), (tx + tw - 1, ty + th - 1)]
            for (sx, sy) in samplePoints {
                let offset = sy * bytesPerRow + sx * 4
                for c in 0..<3 {  // B, G, R channels
                    let va = ptrA.load(fromByteOffset: offset + c, as: UInt8.self)
                    let vb = ptrB.load(fromByteOffset: offset + c, as: UInt8.self)
                    if abs(Int(va) - Int(vb)) > DELTA_CHANGE_THRESHOLD {
                        tileChanged = true
                        break
                    }
                }
                if tileChanged { break }
            }

            if tileChanged {
                changed.append((x: tx, y: ty, w: tw, h: th))
            }
        }
    }

    return changed
}

/// Encode changed tiles into delta frame payload
func encodeDeltaPayload(_ tiles: [(x: Int, y: Int, w: Int, h: Int)],
                         image: CGImage, quality: Int?) -> Data {
    var payload = Data()

    // Tile count (2 bytes BE)
    var count = UInt16(tiles.count).bigEndian
    payload.append(Data(bytes: &count, count: 2))

    for tile in tiles {
        // Tile header: x, y, w, h (each 2 bytes BE)
        var tx = UInt16(tile.x).bigEndian
        var ty = UInt16(tile.y).bigEndian
        var tw = UInt16(tile.w).bigEndian
        var th = UInt16(tile.h).bigEndian
        payload.append(Data(bytes: &tx, count: 2))
        payload.append(Data(bytes: &ty, count: 2))
        payload.append(Data(bytes: &tw, count: 2))
        payload.append(Data(bytes: &th, count: 2))

        // Crop tile from image
        let rect = CGRect(x: tile.x, y: tile.y, width: tile.w, height: tile.h)
        let tileImage = image.cropping(to: rect) ?? image

        // Encode tile as JPEG
        let jpegData = NSMutableData()
        if let dest = CGImageDestinationCreateWithData(jpegData, "public.jpeg" as CFString, 1, nil) {
            CGImageDestinationAddImage(dest, tileImage, [
                kCGImageDestinationLossyCompressionQuality: Double(quality ?? jpegQuality) / 100.0
            ] as CFDictionary)
            CGImageDestinationFinalize(dest)
        }

        // JPEG size (4 bytes BE) + data
        var jpegSize = UInt32(jpegData.length).bigEndian
        payload.append(Data(bytes: &jpegSize, count: 4))
        payload.append(jpegData as Data)
    }

    return payload
}

/// Write frame with type byte prefix
func writeTypedFrame(type: UInt8, _ data: Data, to fd: Int32) -> Bool {
    var t = type
    let typeOk = Darwin.write(fd, &t, 1)
    if typeOk != 1 { return false }
    return writeFrame(data, to: fd)
}

// MARK: - Shared JPEG encoding (used by both SCK and legacy paths)

func encodeJPEG(_ image: CGImage, quality: Int?, maxDim: Int?) -> (data: Data, width: Int, height: Int)? {
    // Create in-memory image source for resizing
    let mutableData = NSMutableData()
    guard let tempDest = CGImageDestinationCreateWithData(mutableData, "public.png" as CFString, 1, nil) else { return nil }
    CGImageDestinationAddImage(tempDest, image, nil)
    guard CGImageDestinationFinalize(tempDest) else { return nil }

    guard let source = CGImageSourceCreateWithData(mutableData, nil) else { return nil }

    let thumbOpts: [CFString: Any] = [
        kCGImageSourceThumbnailMaxPixelSize: maxDim ?? maxDimension,
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true
    ]
    guard let thumb = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOpts as CFDictionary) else { return nil }

    let outData = NSMutableData()
    guard let dest = CGImageDestinationCreateWithData(outData, "public.jpeg" as CFString, 1, nil) else { return nil }
    CGImageDestinationAddImage(dest, thumb, [
        kCGImageDestinationLossyCompressionQuality: Double(quality ?? jpegQuality) / 100.0
    ] as CFDictionary)
    guard CGImageDestinationFinalize(dest) else { return nil }

    return (outData as Data, thumb.width, thumb.height)
}

func cropCGImage(_ image: CGImage, region: (x: Double, y: Double, w: Double, h: Double)) -> CGImage {
    let rx = Int(region.x * Double(image.width))
    let ry = Int(region.y * Double(image.height))
    let rw = Int(region.w * Double(image.width))
    let rh = Int(region.h * Double(image.height))
    let rect = CGRect(x: rx, y: ry, width: rw, height: rh)
    return image.cropping(to: rect) ?? image
}

// MARK: - ScreenCaptureKit capture path

@available(macOS 12.3, *)
func captureScreenSCK(quality: Int? = nil, maxDim: Int? = nil,
                       region: (x: Double, y: Double, w: Double, h: Double)? = nil) -> (data: Data, width: Int, height: Int)? {
    guard let capture = sckCapture as? SCKCapture else { return nil }
    guard let pixelBuffer = capture.grabFrame() else { return nil }

    let t0 = CFAbsoluteTimeGetCurrent()

    var cgImage: CGImage?
    VTCreateCGImageFromCVPixelBuffer(pixelBuffer, options: nil, imageOut: &cgImage)
    guard var image = cgImage else { return nil }
    let t1 = CFAbsoluteTimeGetCurrent()

    if let r = region {
        image = cropCGImage(image, region: r)
    }

    guard let result = encodeJPEG(image, quality: quality, maxDim: maxDim) else { return nil }
    let t2 = CFAbsoluteTimeGetCurrent()

    let convertMs = Int((t1 - t0) * 1000)
    let encodeMs = Int((t2 - t1) * 1000)
    print("  [SCK] convert:\(convertMs)ms encode:\(encodeMs)ms \(result.width)x\(result.height) \(result.data.count/1024)KB")

    return result
}

// MARK: - Capture dispatcher

func captureScreen(quality: Int? = nil, maxDim: Int? = nil,
                   region: (x: Double, y: Double, w: Double, h: Double)? = nil) -> (data: Data, width: Int, height: Int)? {
    #if canImport(ScreenCaptureKit)
    if useScreenCaptureKit, sckCapture != nil {
        if #available(macOS 12.3, *) {
            if let result = captureScreenSCK(quality: quality, maxDim: maxDim, region: region) {
                return result
            }
            // SCK failed, fall through to legacy
        }
    }
    #endif
    return captureScreenLegacy(quality: quality, maxDim: maxDim, region: region)
}

// MARK: - Legacy capture (screencapture subprocess)

func captureScreenLegacy(quality: Int? = nil, maxDim: Int? = nil,
                         region: (x: Double, y: Double, w: Double, h: Double)? = nil) -> (data: Data, width: Int, height: Int)? {
    let t0 = CFAbsoluteTimeGetCurrent()

    let jpgPath = "/tmp/tabletpen-ss.jpg"
    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
    if let r = region {
        // Region capture: convert normalized (0-1) to screen pixel coordinates
        let screenW = CGDisplayPixelsWide(CGMainDisplayID())
        let screenH = CGDisplayPixelsHigh(CGMainDisplayID())
        let rx = Int(r.x * Double(screenW))
        let ry = Int(r.y * Double(screenH))
        let rw = Int(r.w * Double(screenW))
        let rh = Int(r.h * Double(screenH))
        proc.arguments = ["-x", "-t", "jpg", "-R", "\(rx),\(ry),\(rw),\(rh)", jpgPath]
    } else {
        proc.arguments = ["-x", "-t", "jpg", "-r", jpgPath]
    }
    try? proc.run()
    proc.waitUntilExit()
    let t1 = CFAbsoluteTimeGetCurrent()

    guard let source = CGImageSourceCreateWithURL(
        URL(fileURLWithPath: jpgPath) as CFURL, nil
    ) else { print("Failed to load capture"); return nil }

    let thumbOpts: [CFString: Any] = [
        kCGImageSourceThumbnailMaxPixelSize: maxDim ?? maxDimension,
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true
    ]
    guard let thumb = CGImageSourceCreateThumbnailAtIndex(
        source, 0, thumbOpts as CFDictionary
    ) else { print("Resize failed"); return nil }
    let t2 = CFAbsoluteTimeGetCurrent()

    let outData = NSMutableData()
    guard let dest = CGImageDestinationCreateWithData(
        outData, "public.jpeg" as CFString, 1, nil
    ) else { print("JPEG init failed"); return nil }
    CGImageDestinationAddImage(dest, thumb, [
        kCGImageDestinationLossyCompressionQuality: Double(quality ?? jpegQuality) / 100.0
    ] as CFDictionary)
    guard CGImageDestinationFinalize(dest) else { print("JPEG encode failed"); return nil }
    let t3 = CFAbsoluteTimeGetCurrent()

    let captureMs = Int((t1 - t0) * 1000)
    let resizeMs = Int((t2 - t1) * 1000)
    let encodeMs = Int((t3 - t2) * 1000)
    print("  capture:\(captureMs)ms resize:\(resizeMs)ms encode:\(encodeMs)ms \(thumb.width)x\(thumb.height) \(outData.length/1024)KB")

    return (outData as Data, thumb.width, thumb.height)
}

/// Write [4-byte BE size][data] frame
func writeFrame(_ data: Data, to fd: Int32) -> Bool {
    var size = UInt32(data.count).bigEndian
    let sizeOk = withUnsafeBytes(of: &size) { buf in
        Darwin.write(fd, buf.baseAddress!, 4)
    }
    if sizeOk != 4 { return false }

    return data.withUnsafeBytes { buf in
        var off = 0
        while off < data.count {
            let n = Darwin.write(fd, buf.baseAddress!.advanced(by: off), data.count - off)
            if n <= 0 { return false }
            off += n
        }
        return true
    }
}

// MARK: - Get local IP

func getLocalIP() -> String? {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
    defer { freeifaddrs(ifaddr) }

    var ptr: UnsafeMutablePointer<ifaddrs>? = first
    while let ifa = ptr {
        let addr = ifa.pointee.ifa_addr.pointee
        let flags = Int32(ifa.pointee.ifa_flags)
        if addr.sa_family == UInt8(AF_INET)
            && (flags & IFF_UP) != 0
            && (flags & IFF_RUNNING) != 0
            && (flags & IFF_LOOPBACK) == 0 {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(ifa.pointee.ifa_addr, socklen_t(addr.sa_len),
                        &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: hostname)
            if !ip.isEmpty { return ip }
        }
        ptr = ifa.pointee.ifa_next
    }
    return nil
}

// MARK: - Command parsing

struct CaptureParams {
    var quality: Int? = nil
    var maxDim: Int? = nil
    var region: (x: Double, y: Double, w: Double, h: Double)? = nil
}

func parseCommand(_ cmd: String) -> (action: String, params: CaptureParams) {
    let parts = cmd.split(separator: " ", maxSplits: 1)
    let action = String(parts.first ?? "")
    var params = CaptureParams()

    if parts.count > 1 {
        let paramStr = String(parts[1])
        for token in paramStr.split(separator: " ") {
            let kv = token.split(separator: "=", maxSplits: 1)
            guard kv.count == 2 else { continue }
            let key = String(kv[0])
            let val_ = String(kv[1])
            switch key {
            case "q": params.quality = Int(val_)
            case "max": params.maxDim = Int(val_)
            case "r":
                let coords = val_.split(separator: ",").compactMap { Double($0) }
                if coords.count == 4 {
                    params.region = (x: coords[0], y: coords[1],
                                     w: coords[2] - coords[0], h: coords[3] - coords[1])
                }
            default: break
            }
        }
    }

    return (action, params)
}

// MARK: - WiFi TCP Server

func startWifiServer() {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { print("WiFi: socket() failed"); return }

    var yes: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = wifiPort.bigEndian
    addr.sin_addr.s_addr = INADDR_ANY

    let bindOk = withUnsafePointer(to: &addr) { ptr in
        ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    guard bindOk == 0 else { print("WiFi: bind() failed"); close(fd); return }
    guard listen(fd, 5) == 0 else { print("WiFi: listen() failed"); close(fd); return }

    if let ip = getLocalIP() {
        print("WiFi server: \(ip):\(wifiPort)")
    } else {
        print("WiFi server: port \(wifiPort) (could not determine IP)")
    }

    DispatchQueue.global().async {
        while true {
            var clientAddr = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let clientFd = withUnsafeMutablePointer(to: &clientAddr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    accept(fd, $0, &len)
                }
            }
            guard clientFd >= 0 else { continue }
            print("WiFi: client connected")
            DispatchQueue.global().async { handleWifiClient(fd: clientFd) }
        }
    }
}

func handleWifiClient(fd: Int32) {
    // Read commands line by line
    var lineBuf = [UInt8]()

    while true {
        var byte: UInt8 = 0
        let n = Darwin.read(fd, &byte, 1)
        if n <= 0 { break }

        if byte == UInt8(ascii: "\n") {
            let cmd = String(bytes: lineBuf, encoding: .utf8)?.trimmingCharacters(in: .whitespaces) ?? ""
            lineBuf.removeAll()

            let (action, params) = parseCommand(cmd)
            if action == "screenshot" {
                let t0 = CFAbsoluteTimeGetCurrent()
                guard let frame = captureScreen(quality: params.quality, maxDim: params.maxDim, region: params.region) else { continue }
                if !writeFrame(frame.data, to: fd) { break }
                let totalMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
                print("WiFi screenshot: \(frame.data.count/1024)KB q=\(params.quality ?? jpegQuality) total:\(totalMs)ms")
            } else if action == "stream" {
                print("WiFi: streaming started")
                var usedPush = false
                #if canImport(ScreenCaptureKit)
                if #available(macOS 12.3, *), let capture = sckCapture as? SCKCapture,
                   capture.running {
                    // Push-model: SCK callback drives frame encoding + sending
                    print("WiFi: using SCK push-model")
                    let sem = capture.startPushStream(fd: fd, params: params)
                    // Wait up to 3s for first frame; if none arrive, SCK is stale — fall back
                    let result = sem.wait(timeout: .now() + 3.0)
                    if result == .timedOut && capture.pushFrameCount == 0 {
                        print("WiFi: SCK push-model sent 0 frames in 3s — falling back to legacy")
                        capture.stopPushStream()
                    } else if result == .timedOut {
                        // Frames are flowing, keep waiting
                        sem.wait()
                        capture.stopPushStream()
                        usedPush = true
                    } else {
                        capture.stopPushStream()
                        usedPush = true
                    }
                }
                #endif
                if !usedPush {
                    streamLoop(fd: fd, params: params)  // legacy fallback
                }
                print("WiFi: streaming stopped")
            } else if cmd == "stop" {
                break
            }
        } else {
            lineBuf.append(byte)
        }
    }

    close(fd)
    print("WiFi: client disconnected")
}

var streamFramesSinceKey = 0
var streamPreviousCGImage: CGImage? = nil

func streamLoop(fd: Int32, params: CaptureParams = CaptureParams()) {
    streamFramesSinceKey = 0
    streamPreviousCGImage = nil
    // Legacy streamLoop: only used on macOS < 12.3 (no SCK push-model available)
    // Make socket non-blocking to check for "stop" command while streaming
    let flags = fcntl(fd, F_GETFL)
    fcntl(fd, F_SETFL, flags | O_NONBLOCK)

    while true {
        // Check for "stop" command (non-blocking read)
        var buf = [UInt8](repeating: 0, count: 64)
        let n = Darwin.read(fd, &buf, buf.count)
        if n > 0 {
            let msg = String(bytes: buf[0..<n], encoding: .utf8) ?? ""
            if msg.contains("stop") { break }
        } else if n == 0 {
            break // Client disconnected
        }
        // n < 0 with EAGAIN = no data, continue

        // Restore blocking for write
        fcntl(fd, F_SETFL, flags)

        // Try delta compression for streaming (SCK path only)
        var sent = false

        // Force legacy path for streaming — SCK delta path needs more work
        // (adaptive FPS filter is fixed but SCK frame buffering still causes issues)
        let useScKForStream = false
        if useScKForStream {
        #if canImport(ScreenCaptureKit)
        if #available(macOS 12.3, *) {
            if let capture = sckCapture as? SCKCapture,
               let currentBuf = capture.grabFrame() {
                var cgImage: CGImage?
                VTCreateCGImageFromCVPixelBuffer(currentBuf, options: nil, imageOut: &cgImage)

                if let image = cgImage {
                    let needsKey = streamFramesSinceKey >= KEY_FRAME_INTERVAL || streamPreviousCGImage == nil

                    if needsKey {
                        // Key frame: full JPEG
                        if let frame = encodeJPEG(image, quality: params.quality, maxDim: params.maxDim) {
                            if !writeTypedFrame(type: 0x02, frame.data, to: fd) { break }
                            print("  [key] \(frame.data.count/1024)KB")
                            streamFramesSinceKey = 0
                            sent = true
                        }
                    } else if capture.previousFrame == nil {
                        // No previous frame for delta — send key frame instead
                        if let frame = encodeJPEG(image, quality: params.quality, maxDim: params.maxDim) {
                            if !writeTypedFrame(type: 0x02, frame.data, to: fd) { break }
                            streamFramesSinceKey = 0
                            sent = true
                        }
                    } else if let prevImage = streamPreviousCGImage,
                              let prevBuf = capture.previousFrame {
                        // Delta frame
                        let tiles = identifyChangedTiles(currentBuf, prevBuf)
                        if tiles.isEmpty {
                            // No change — skip frame
                            sent = true
                            fcntl(fd, F_SETFL, flags | O_NONBLOCK)
                            usleep(10_000)
                            continue
                        } else if tiles.count > (image.width / TILE_SIZE + 1) * (image.height / TILE_SIZE + 1) / 2 {
                            // >50% changed — send key frame
                            if let frame = encodeJPEG(image, quality: params.quality, maxDim: params.maxDim) {
                                if !writeTypedFrame(type: 0x02, frame.data, to: fd) { break }
                                print("  [key-forced] \(frame.data.count/1024)KB (\(tiles.count) tiles changed)")
                                streamFramesSinceKey = 0
                                sent = true
                            }
                        } else {
                            // Delta: encode only changed tiles
                            let payload = encodeDeltaPayload(tiles, image: image, quality: params.quality)
                            if !writeTypedFrame(type: 0x01, payload, to: fd) { break }
                            print("  [delta] \(tiles.count) tiles \(payload.count/1024)KB")
                            streamFramesSinceKey += 1
                            sent = true
                        }
                    }
                    streamPreviousCGImage = image
                }
            }
        }
        #endif

        } // useScKForStream
        if !sent {
            // Full frame via legacy screencapture (SCK grabFrame returns stale buffers during streaming)
            guard let frame = captureScreenLegacy(quality: params.quality, maxDim: params.maxDim, region: params.region) else {
                usleep(100_000)
                fcntl(fd, F_SETFL, flags | O_NONBLOCK)
                continue
            }
            if !writeTypedFrame(type: 0x00, frame.data, to: fd) { break }
        }

        // Back to non-blocking for next check
        fcntl(fd, F_SETFL, flags | O_NONBLOCK)
    }

    // Restore blocking mode
    fcntl(fd, F_SETFL, flags)
}

// MARK: - Bluetooth RFCOMM Server

class ScreenshotServer: NSObject, IOBluetoothRFCOMMChannelDelegate {
    var channel: IOBluetoothRFCOMMChannel?
    var tablet: IOBluetoothDevice?
    var targetName: String?
    private var livenessTimer: Timer?
    private var lastDataTime: CFAbsoluteTime = 0

    // Saved last device for fast reconnect
    private var lastDeviceAddress: String? {
        get { UserDefaults.standard.string(forKey: "lastDevice") }
        set { UserDefaults.standard.set(newValue, forKey: "lastDevice") }
    }
    private var lastDeviceChannel: BluetoothRFCOMMChannelID {
        get { BluetoothRFCOMMChannelID(UserDefaults.standard.integer(forKey: "lastChannel")) }
        set { UserDefaults.standard.set(Int(newValue), forKey: "lastChannel") }
    }

    func start() {
        if channel != nil {
            print("Already connected — skipping scan")
            return
        }

        // 1. Try last known device first (instant, no scanning)
        if let addr = lastDeviceAddress, lastDeviceChannel > 0 {
            let bt = IOBluetoothDevice(addressString: addr)
            if let bt = bt {
                print("Trying last device: \(bt.name ?? addr) ch=\(lastDeviceChannel)")
                attemptConnect(bt, channel: lastDeviceChannel, fallback: { self.scanForTabletPen() })
                return
            }
        }

        // 2. Fall back to full scan
        scanForTabletPen()
    }

    /// Scan paired devices for TabletPen RFCOMM service
    private func scanForTabletPen() {
        if channel != nil { return }

        guard let allDevices = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else {
            print("No paired devices"); exit(1)
        }

        // Deduplicate by address (macOS lists some devices multiple times)
        var seen = Set<String>()
        var devices = [IOBluetoothDevice]()
        for d in allDevices {
            let addr = d.addressString ?? ""
            if !addr.isEmpty && !seen.contains(addr) {
                seen.insert(addr)
                devices.append(d)
            }
        }

        // Filter by name if specified
        if let name = targetName {
            devices = devices.filter { ($0.name ?? "").localizedCaseInsensitiveContains(name) }
        }

        if devices.isEmpty {
            print("No candidates found. Retrying in 2s...")
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.start() }
            return
        }

        // Find devices with TabletPen SDP service
        print("Scanning \(devices.count) devices for TabletPen service...")
        findTabletPenDevices(devices, index: 0, found: [])
    }

    /// SDP scan to collect all devices with TabletPen service, then try connecting
    private func findTabletPenDevices(_ devices: [IOBluetoothDevice], index: Int,
                                       found: [(IOBluetoothDevice, BluetoothRFCOMMChannelID)]) {
        if channel != nil { return }

        if index >= devices.count {
            if found.isEmpty {
                print("No TabletPen devices found. Retrying in 2s...")
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.start() }
            } else {
                // Sort: put last-connected device first
                var sorted = found
                if let lastAddr = self.lastDeviceAddress {
                    sorted.sort { a, b in
                        let aMatch = a.0.addressString == lastAddr
                        let bMatch = b.0.addressString == lastAddr
                        if aMatch && !bMatch { return true }
                        if !aMatch && bMatch { return false }
                        return false
                    }
                }
                for (dev, ch) in sorted {
                    print("  \(dev.name ?? "?") ch=\(ch)\(dev.addressString == self.lastDeviceAddress ? " (last connected)" : "")")
                }
                tryConnectList(sorted, index: 0)
            }
            return
        }

        let dev = devices[index]
        DispatchQueue.global().async {
            let result = dev.performSDPQuery(nil)
            DispatchQueue.main.async {
                if self.channel != nil { return }
                var newFound = found
                if result == kIOReturnSuccess, let services = dev.services as? [IOBluetoothSDPServiceRecord] {
                    for svc in services {
                        var ch: BluetoothRFCOMMChannelID = 0
                        let svcName = svc.getAttributeDataElement(0x0100)?.getStringValue() ?? ""
                        if svc.getRFCOMMChannelID(&ch) == kIOReturnSuccess {
                            if svcName.contains("TabletPen") || svcName.contains("Screenshot") {
                                print("  \(dev.name ?? "?"): TabletPen on ch=\(ch)")
                                newFound.append((dev, ch))
                                break
                            }
                        }
                    }
                }
                self.findTabletPenDevices(devices, index: index + 1, found: newFound)
            }
        }
    }

    /// Try connecting to each TabletPen device, with patience for async callbacks
    private func tryConnectList(_ devices: [(IOBluetoothDevice, BluetoothRFCOMMChannelID)], index: Int) {
        if channel != nil { return }
        if index >= devices.count {
            print("All connection attempts failed. Retrying in 2s...")
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.start() }
            return
        }

        let (dev, ch) = devices[index]
        print("Connecting to \(dev.name ?? "?") ch=\(ch)...")
        attemptConnect(dev, channel: ch) {
            self.tryConnectList(devices, index: index + 1)
        }
    }

    /// Try RFCOMM connection with exponential backoff.
    /// Sync call often fails but async delegate may succeed.
    private var connectRetryDelay: Double = 0.5

    private func attemptConnect(_ device: IOBluetoothDevice, channel ch: BluetoothRFCOMMChannelID,
                                 fallback: @escaping () -> Void) {
        DispatchQueue.global().async {
            var rfcomm: IOBluetoothRFCOMMChannel?
            let result = device.openRFCOMMChannelSync(&rfcomm, withChannelID: ch, delegate: self)
            if result == kIOReturnSuccess, let rfcomm = rfcomm {
                self.channel = rfcomm
                self.connectRetryDelay = 0.5 // reset on success
            } else {
                let wait = self.connectRetryDelay
                print("  Sync open failed (\(result)) — waiting \(String(format: "%.1f", wait))s for async callback...")
                DispatchQueue.main.asyncAfter(deadline: .now() + wait) {
                    if self.channel == nil {
                        print("  No async connection after \(String(format: "%.1f", wait))s")
                        // Exponential backoff: 0.5 → 1 → 2 → 4 → 8 → 15 (cap)
                        self.connectRetryDelay = min(self.connectRetryDelay * 2, 15.0)
                        fallback()
                    } else {
                        self.connectRetryDelay = 0.5 // reset on success
                    }
                }
            }
        }
    }

    /// Send WiFi server info over RFCOMM
    private func sendWifiInfo(channel: IOBluetoothRFCOMMChannel) {
        guard let ip = getLocalIP() else {
            print("Could not determine local IP — WiFi transfer unavailable")
            return
        }
        let info = "wifi:\(ip):\(wifiPort)\n"
        let data = info.data(using: .utf8)!
        data.withUnsafeBytes { buf in
            _ = channel.writeSync(UnsafeMutableRawPointer(mutating: buf.baseAddress!), length: UInt16(data.count))
        }
        print("Sent WiFi info: \(info.trimmingCharacters(in: .newlines))")
    }

    // MARK: - RFCOMM Delegate

    func rfcommChannelOpenComplete(_ ch: IOBluetoothRFCOMMChannel!, status err: IOReturn) {
        if err == kIOReturnSuccess {
            channel = ch
            tablet = ch.getDevice()
            // Save for fast reconnect next time
            if let addr = tablet?.addressString {
                lastDeviceAddress = addr
                lastDeviceChannel = ch.getID()
            }
            print("BT connected to \(tablet?.name ?? "?") on ch=\(ch.getID())")
            self.lastDataTime = CFAbsoluteTimeGetCurrent()
            self.startLivenessTimer()
            sendWifiInfo(channel: ch)
        } else {
            print("BT delegate: connection failed (\(err))")
        }
    }

    func rfcommChannelData(_ ch: IOBluetoothRFCOMMChannel!,
                           data ptr: UnsafeMutableRawPointer!, length len: Int) {
        guard let ptr = ptr else { return }
        let cmd = String(data: Data(bytes: ptr, count: len), encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.lastDataTime = CFAbsoluteTimeGetCurrent()
        print("BT received: '\(cmd)'")
        let (action, btParams) = parseCommand(cmd)
        if action == "screenshot" {
            DispatchQueue.global().async { self.sendScreenshotBT(channel: ch, params: btParams) }
        } else if action.hasPrefix("wifiserver") {
            // Tablet is offering a reverse WiFi server — connect outbound
            let parts = cmd.replacingOccurrences(of: "wifiserver:", with: "").split(separator: ":")
            if parts.count == 2, let port = Int(parts[1]) {
                let host = String(parts[0])
                print("Tablet offers reverse WiFi: \(host):\(port)")
                DispatchQueue.global().async { self.connectToTabletWifi(host: host, port: port) }
            }
        }
    }

    /// Connect outbound to tablet's TCP server (reverse WiFi — bypasses Mac firewall/CrowdStrike)
    private func connectToTabletWifi(host: String, port: Int) {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { print("WiFi reverse: socket() failed"); return }

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian
        addr.sin_addr.s_addr = inet_addr(host)

        let result = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        if result == 0 {
            print("WiFi reverse connected to tablet \(host):\(port)")
            DispatchQueue.global().async { handleWifiClient(fd: fd) }
        } else {
            print("WiFi reverse connect failed to \(host):\(port)")
            close(fd)
        }
    }

    func rfcommChannelClosed(_ ch: IOBluetoothRFCOMMChannel!) {
        print("BT disconnected from \(tablet?.name ?? "?")")
        channel = nil
        tablet = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.start()
        }
    }

    private func startLivenessTimer() {
        stopLivenessTimer()
        livenessTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            guard let self = self, let ch = self.channel else { return }
            let elapsed = CFAbsoluteTimeGetCurrent() - self.lastDataTime
            // If no data for 30s and channel exists, try a write to test liveness
            if elapsed > 30 {
                // Try writing a no-op byte to test channel liveness
                var ping: UInt8 = 0
                let result = ch.writeSync(&ping, length: 0) // zero-length write tests connection
                if result != kIOReturnSuccess {
                    print("BT channel stale (no data for \(Int(elapsed))s, write failed) — reconnecting")
                    self.stopLivenessTimer()
                    self.channel = nil
                    self.tablet = nil
                    self.connectRetryDelay = 0.5
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.start() }
                }
            }
        }
    }

    private func stopLivenessTimer() {
        livenessTimer?.invalidate()
        livenessTimer = nil
    }

    private func sendScreenshotBT(channel: IOBluetoothRFCOMMChannel, params: CaptureParams = CaptureParams()) {
        let t0 = CFAbsoluteTimeGetCurrent()
        guard let frame = captureScreen(quality: params.quality, maxDim: params.maxDim, region: params.region) else { return }

        var size = UInt32(frame.data.count).bigEndian
        withUnsafeMutablePointer(to: &size) { p in _ = channel.writeSync(p, length: 4) }

        let mtu = max(Int(channel.getMTU()), 672)
        var off = 0
        while off < frame.data.count {
            let n = min(mtu, frame.data.count - off)
            frame.data.withUnsafeBytes { buf in
                _ = channel.writeSync(
                    UnsafeMutableRawPointer(mutating: buf.baseAddress!.advanced(by: off)),
                    length: UInt16(n))
            }
            off += n
        }
        let totalMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        print("BT screenshot: \(frame.data.count/1024)KB total:\(totalMs)ms")
    }
}

// MARK: - Main

startWifiServer()

// Initialize ScreenCaptureKit if available
#if canImport(ScreenCaptureKit)
if useScreenCaptureKit {
    if #available(macOS 12.3, *) {
        let capture = SCKCapture()
        sckCapture = capture
        Task {
            do {
                try await capture.start()
                print("ScreenCaptureKit: active (30+ FPS capable)")
            } catch {
                print("ScreenCaptureKit unavailable: \(error.localizedDescription) — using screencapture fallback")
                sckCapture = nil
            }
        }
    }
} else {
    print("macOS < 12.3 — using screencapture fallback")
}
#else
print("ScreenCaptureKit not available — using screencapture fallback")
#endif

let server = ScreenshotServer()

var args = Array(CommandLine.arguments.dropFirst())
while !args.isEmpty {
    let arg = args.removeFirst()
    if arg == "--quality" || arg == "-q", let v = args.first, let i = Int(v) {
        jpegQuality = i; args.removeFirst()
    } else if arg == "--max" || arg == "-m", let v = args.first, let i = Int(v) {
        maxDimension = i; args.removeFirst()
    } else if !arg.hasPrefix("-") {
        server.targetName = arg
    }
}
if let name = server.targetName {
    print("Device filter: \(name)")
}
print("Quality: \(jpegQuality)%, Max: \(maxDimension)px")
server.start()
signal(SIGINT) { _ in exit(0) }
RunLoop.main.run()
