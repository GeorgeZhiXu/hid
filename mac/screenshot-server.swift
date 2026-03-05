import Foundation
import IOBluetooth
import ImageIO

setbuf(stdout, nil)

// Screenshot quality settings
var maxDimension = 1280  // max width or height in pixels
var jpegQuality = 35     // 1-100, lower = smaller + faster
let wifiPort: UInt16 = 9877

let kServiceUUID = "A5D3E9F0-7B1C-4C2E-9F3A-B8C1D2E4F6A7"

// MARK: - Shared capture function

func captureScreen() -> (data: Data, width: Int, height: Int)? {
    let t0 = CFAbsoluteTimeGetCurrent()

    let jpgPath = "/tmp/tabletpen-ss.jpg"
    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
    proc.arguments = ["-x", "-t", "jpg", "-r", jpgPath]
    try? proc.run()
    proc.waitUntilExit()
    let t1 = CFAbsoluteTimeGetCurrent()

    guard let source = CGImageSourceCreateWithURL(
        URL(fileURLWithPath: jpgPath) as CFURL, nil
    ) else { print("Failed to load capture"); return nil }

    let thumbOpts: [CFString: Any] = [
        kCGImageSourceThumbnailMaxPixelSize: maxDimension,
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
        kCGImageDestinationLossyCompressionQuality: Double(jpegQuality) / 100.0
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

            if cmd == "screenshot" {
                let t0 = CFAbsoluteTimeGetCurrent()
                guard let frame = captureScreen() else { continue }
                if !writeFrame(frame.data, to: fd) { break }
                let totalMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
                print("WiFi screenshot: \(frame.data.count/1024)KB total:\(totalMs)ms")
            } else if cmd == "stream" {
                print("WiFi: streaming started")
                streamLoop(fd: fd)
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

func streamLoop(fd: Int32) {
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

        guard let frame = captureScreen() else {
            usleep(100_000) // 100ms retry
            fcntl(fd, F_SETFL, flags | O_NONBLOCK)
            continue
        }
        if !writeFrame(frame.data, to: fd) { break }

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

    func start() {
        // Skip scan if already connected (handles async connection success during scan)
        if channel != nil {
            print("Already connected — skipping scan")
            return
        }

        guard let devices = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else {
            print("No paired devices"); exit(1)
        }

        print("Searching for TabletPen RFCOMM service...")
        for d in devices {
            print("  \(d.name ?? "?")")
        }

        let candidates: [IOBluetoothDevice]
        if let name = targetName {
            candidates = devices.filter { ($0.name ?? "").localizedCaseInsensitiveContains(name) }
            if candidates.isEmpty {
                print("No device matching '\(name)'. Retrying in 5s...")
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.start() }
                return
            }
        } else {
            candidates = devices
        }

        tryDevices(candidates, index: 0)
    }

    private var scanDevices: [IOBluetoothDevice] = []
    private var scanIndex: Int = 0

    private func tryDevices(_ devices: [IOBluetoothDevice], index: Int) {
        scanDevices = devices
        scanIndex = index
        tryNextDevice()
    }

    private func tryNextDevice() {
        if channel != nil { return } // connected while scanning

        if scanIndex >= scanDevices.count {
            print("No device connected yet. Retrying in 5s...")
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.start() }
            return
        }

        let dev = scanDevices[scanIndex]
        let name = dev.name ?? "?"
        print("Trying \(name)...")

        DispatchQueue.global().async {
            let result = dev.performSDPQuery(nil)
            DispatchQueue.main.async {
                if result == kIOReturnSuccess, let services = dev.services as? [IOBluetoothSDPServiceRecord] {
                    for svc in services {
                        var ch: BluetoothRFCOMMChannelID = 0
                        let svcName = svc.getAttributeDataElement(0x0100)?.getStringValue() ?? ""
                        if svc.getRFCOMMChannelID(&ch) == kIOReturnSuccess {
                            if svcName.contains("TabletPen") || svcName.contains("Screenshot") {
                                print("Found TabletPen on \(name) ch=\(ch), connecting...")
                                self.openChannel(dev, channel: ch)
                                return
                            }
                        }
                    }
                }
                self.scanIndex += 1
                self.tryNextDevice()
            }
        }
    }

    func connectToTablet(_ device: IOBluetoothDevice) {
        let result = device.performSDPQuery(nil)
        if result != kIOReturnSuccess {
            print("SDP query failed, retrying...")
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self.connectToTablet(device) }
            return
        }

        guard let services = device.services as? [IOBluetoothSDPServiceRecord] else {
            print("No SDP services, retrying...")
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self.connectToTablet(device) }
            return
        }

        for svc in services {
            var ch: BluetoothRFCOMMChannelID = 0
            let name = svc.getAttributeDataElement(0x0100)?.getStringValue() ?? ""
            if svc.getRFCOMMChannelID(&ch) == kIOReturnSuccess {
                if name.contains("TabletPen") || name.contains("Screenshot") {
                    print("Found TabletPen service on ch=\(ch)!")
                    openChannel(device, channel: ch)
                    return
                }
            }
        }

        print("Service not found, retrying in 5s...")
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.connectToTablet(device) }
    }

    func openChannel(_ device: IOBluetoothDevice, channel ch: BluetoothRFCOMMChannelID) {
        DispatchQueue.global().async {
            var rfcomm: IOBluetoothRFCOMMChannel?
            let result = device.openRFCOMMChannelSync(&rfcomm, withChannelID: ch, delegate: self)
            if result == kIOReturnSuccess, let rfcomm = rfcomm {
                self.channel = rfcomm
                // WiFi info sent from rfcommChannelOpenComplete delegate
            } else {
                print("Failed to open ch=\(ch) on \(device.name ?? "?"): \(result) — trying next device")
                DispatchQueue.main.async {
                    self.scanIndex += 1
                    self.tryNextDevice()
                }
            }
        }
    }

    /// Send WiFi server info over RFCOMM so tablet can connect directly
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
            tablet = ch.getDevice()  // only set tablet on successful connection
            print("BT connected to \(tablet?.name ?? "?") on ch=\(ch.getID())")
            sendWifiInfo(channel: ch)
        } else {
            // Don't retry here — openChannel failure handler manages the scan
            print("BT delegate: connection failed (\(err))")
        }
    }

    func rfcommChannelData(_ ch: IOBluetoothRFCOMMChannel!,
                           data ptr: UnsafeMutableRawPointer!, length len: Int) {
        guard let ptr = ptr else { return }
        let cmd = String(data: Data(bytes: ptr, count: len), encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        print("BT received: '\(cmd)'")
        if cmd == "screenshot" {
            DispatchQueue.global().async { self.sendScreenshotBT(channel: ch) }
        }
    }

    func rfcommChannelClosed(_ ch: IOBluetoothRFCOMMChannel!) {
        print("BT disconnected from \(tablet?.name ?? "?")")
        channel = nil
        tablet = nil
        // Full scan on reconnect — the device may have changed
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.start()
        }
    }

    private func sendScreenshotBT(channel: IOBluetoothRFCOMMChannel) {
        let t0 = CFAbsoluteTimeGetCurrent()
        guard let frame = captureScreen() else { return }

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
