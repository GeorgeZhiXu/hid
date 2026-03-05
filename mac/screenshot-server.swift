import Foundation
import IOBluetooth
import ImageIO

setbuf(stdout, nil)

// Screenshot quality settings
var maxDimension = 1280  // max width or height in pixels
var jpegQuality = 35     // 1-100, lower = smaller + faster

let kServiceUUID = "A5D3E9F0-7B1C-4C2E-9F3A-B8C1D2E4F6A7"

class ScreenshotServer: NSObject, IOBluetoothRFCOMMChannelDelegate {
    var channel: IOBluetoothRFCOMMChannel?
    var tablet: IOBluetoothDevice?

    var targetName: String?

    func start() {
        guard let devices = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else {
            print("No paired devices"); exit(1)
        }

        print("Searching for TabletPen RFCOMM service...")
        for d in devices {
            print("  \(d.name ?? "?")")
        }

        // If user specified a device name, filter to that
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

        // Try SDP query on each candidate to find TabletPen service
        tryDevices(candidates, index: 0)
    }

    private func tryDevices(_ devices: [IOBluetoothDevice], index: Int) {
        if index >= devices.count {
            print("TabletPen service not found on any device. Retrying in 5s...")
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.start() }
            return
        }

        let dev = devices[index]
        let name = dev.name ?? "?"
        print("Trying \(name)...")

        // SDP query — will fail quickly for unreachable devices
        DispatchQueue.global().async {
            let result = dev.performSDPQuery(nil)
            DispatchQueue.main.async {
                if result == kIOReturnSuccess, let services = dev.services as? [IOBluetoothSDPServiceRecord] {
                    // Check if this device has the TabletPen Screenshot service
                    for svc in services {
                        var ch: BluetoothRFCOMMChannelID = 0
                        let svcName = svc.getAttributeDataElement(0x0100)?.getStringValue() ?? ""
                        if svc.getRFCOMMChannelID(&ch) == kIOReturnSuccess {
                            if svcName.contains("TabletPen") || svcName.contains("Screenshot") {
                                print("Found TabletPen on \(name) ch=\(ch)!")
                                self.tablet = dev
                                self.openChannel(dev, channel: ch)
                                return
                            }
                        }
                    }
                }
                // Not found on this device, try next
                self.tryDevices(devices, index: index + 1)
            }
        }
    }

    func connectToTablet(_ device: IOBluetoothDevice) {
        // SDP query to find our RFCOMM service
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

        // Try all RFCOMM services
        print("Found \(services.count) services:")
        for svc in services {
            var ch: BluetoothRFCOMMChannelID = 0
            let name = svc.getAttributeDataElement(0x0100)?.getStringValue() ?? "?"
            if svc.getRFCOMMChannelID(&ch) == kIOReturnSuccess {
                print("  ch=\(ch): \(name)")
            }
        }

        // Find "TabletPen Screenshot" or try all RFCOMM channels
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

        // Not found by name — try connecting to each RFCOMM channel
        print("Service not found by name, trying all channels...")
        for svc in services {
            var ch: BluetoothRFCOMMChannelID = 0
            if svc.getRFCOMMChannelID(&ch) == kIOReturnSuccess && ch > 0 {
                print("Trying ch=\(ch)...")
                openChannel(device, channel: ch)
                return
            }
        }

        print("No RFCOMM services found. Make sure TabletPen app is running.")
        print("Retrying in 5s...")
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.connectToTablet(device) }
    }

    func openChannel(_ device: IOBluetoothDevice, channel ch: BluetoothRFCOMMChannelID) {
        // Use sync on background thread to avoid blocking RunLoop
        DispatchQueue.global().async {
            var rfcomm: IOBluetoothRFCOMMChannel?
            let result = device.openRFCOMMChannelSync(&rfcomm, withChannelID: ch, delegate: self)
            if result == kIOReturnSuccess, let rfcomm = rfcomm {
                self.channel = rfcomm
                print("Connected to tablet on ch=\(ch)!")
                print("Ready — tap Screenshot on the tablet.")
            } else {
                print("Failed to open ch=\(ch): \(result)")
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                    self.connectToTablet(device)
                }
            }
        }
    }

    // MARK: - Delegate

    func rfcommChannelOpenComplete(_ ch: IOBluetoothRFCOMMChannel!, status err: IOReturn) {
        if err == kIOReturnSuccess {
            channel = ch
            print("Connected to tablet on ch=\(ch.getID())!")
            print("Ready — tap Screenshot on the tablet.")
        } else {
            print("Connection failed: \(err)")
            if let t = tablet {
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self.connectToTablet(t) }
            }
        }
    }

    func rfcommChannelData(_ ch: IOBluetoothRFCOMMChannel!,
                           data ptr: UnsafeMutableRawPointer!, length len: Int) {
        guard let ptr = ptr else { return }
        let cmd = String(data: Data(bytes: ptr, count: len), encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        print("Received: '\(cmd)'")
        if cmd == "screenshot" { sendScreenshot(channel: ch) }
    }

    func rfcommChannelClosed(_ ch: IOBluetoothRFCOMMChannel!) {
        print("Disconnected from tablet")
        channel = nil
        // Reconnect
        if let t = tablet {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.connectToTablet(t) }
        }
    }

    private func sendScreenshot(channel: IOBluetoothRFCOMMChannel) {
        let t0 = CFAbsoluteTimeGetCurrent()

        // Capture directly as JPEG (single subprocess, no PNG→JPEG conversion)
        let jpgPath = "/tmp/tabletpen-ss.jpg"
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        proc.arguments = ["-x", "-t", "jpg", "-r", jpgPath]
        try? proc.run()
        proc.waitUntilExit()
        let t1 = CFAbsoluteTimeGetCurrent()

        // Load and resize + recompress in-memory via ImageIO
        guard let source = CGImageSourceCreateWithURL(
            URL(fileURLWithPath: jpgPath) as CFURL, nil
        ) else { print("Failed to load capture"); return }

        // Thumbnail with max dimension — fast hardware-accelerated resize
        let thumbOpts: [CFString: Any] = [
            kCGImageSourceThumbnailMaxPixelSize: maxDimension,
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]
        guard let thumb = CGImageSourceCreateThumbnailAtIndex(
            source, 0, thumbOpts as CFDictionary
        ) else { print("Resize failed"); return }
        let t2 = CFAbsoluteTimeGetCurrent()

        // Re-encode to JPEG at target quality (in memory)
        let outData = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            outData, "public.jpeg" as CFString, 1, nil
        ) else { print("JPEG init failed"); return }
        CGImageDestinationAddImage(dest, thumb, [
            kCGImageDestinationLossyCompressionQuality: Double(jpegQuality) / 100.0
        ] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { print("JPEG encode failed"); return }
        let t3 = CFAbsoluteTimeGetCurrent()

        // Send over Bluetooth
        let bytes = outData as Data
        var size = UInt32(bytes.count).bigEndian
        withUnsafeMutablePointer(to: &size) { p in _ = channel.writeSync(p, length: 4) }

        let mtu = max(Int(channel.getMTU()), 672)
        var off = 0
        while off < bytes.count {
            let n = min(mtu, bytes.count - off)
            bytes.withUnsafeBytes { buf in
                _ = channel.writeSync(
                    UnsafeMutableRawPointer(mutating: buf.baseAddress!.advanced(by: off)),
                    length: UInt16(n))
            }
            off += n
        }
        let t4 = CFAbsoluteTimeGetCurrent()

        let captureMs = Int((t1 - t0) * 1000)
        let resizeMs = Int((t2 - t1) * 1000)
        let encodeMs = Int((t3 - t2) * 1000)
        let sendMs = Int((t4 - t3) * 1000)
        let totalMs = Int((t4 - t0) * 1000)
        let kb = bytes.count / 1024
        print("\(thumb.width)x\(thumb.height) \(kb)KB — capture:\(captureMs)ms resize:\(resizeMs)ms encode:\(encodeMs)ms send:\(sendMs)ms total:\(totalMs)ms")
    }
}

let server = ScreenshotServer()

// Parse args: [device_name] [--quality 0.35] [--max 1280]
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
print("Quality: \(jpegQuality), Max dimension: \(maxDimension)px")
server.start()
signal(SIGINT) { _ in exit(0) }
RunLoop.main.run()
