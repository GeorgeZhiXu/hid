import Foundation
import IOBluetooth

setbuf(stdout, nil)

let kServiceUUID = "A5D3E9F0-7B1C-4C2E-9F3A-B8C1D2E4F6A7"

class ScreenshotServer: NSObject, IOBluetoothRFCOMMChannelDelegate {
    var channel: IOBluetoothRFCOMMChannel?
    var tablet: IOBluetoothDevice?

    func start() {
        // Find the paired tablet that's connected
        guard let devices = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else {
            print("No paired devices"); exit(1)
        }

        print("Looking for connected tablet...")
        for d in devices {
            print("  \(d.name ?? "?") connected=\(d.isConnected())")
        }

        guard let dev = devices.first(where: { $0.isConnected() }) else {
            print("No connected device. Make sure tablet is connected via HID first.")
            print("Retrying in 5s...")
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.start() }
            return
        }

        tablet = dev
        print("Found: \(dev.name ?? "?"), querying SDP...")
        connectToTablet(dev)
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
        let path = "/tmp/tabletpen-ss.png"
        let jpgPath = "/tmp/tabletpen-ss.jpg"
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        proc.arguments = ["-x", "-t", "png", "-r", path]
        try? proc.run()
        proc.waitUntilExit()

        // Convert to lower quality JPEG (50%) and scale down for faster BT transfer
        let convert = Process()
        convert.executableURL = URL(fileURLWithPath: "/usr/bin/sips")
        convert.arguments = ["-s", "formatOptions", "50",
                             "-Z", "1920",  // max dimension 1920px
                             "-s", "format", "jpeg",
                             path, "--out", jpgPath]
        try? convert.run()
        convert.waitUntilExit()

        guard let data = try? Data(contentsOf: URL(fileURLWithPath: jpgPath)) else {
            print("Capture failed"); return
        }
        print("Sending \(data.count) bytes...")

        var size = UInt32(data.count).bigEndian
        withUnsafeMutablePointer(to: &size) { p in _ = channel.writeSync(p, length: 4) }

        let mtu = max(Int(channel.getMTU()), 672)
        var off = 0
        while off < data.count {
            let n = min(mtu, data.count - off)
            data.withUnsafeBytes { buf in
                _ = channel.writeSync(
                    UnsafeMutableRawPointer(mutating: buf.baseAddress!.advanced(by: off)),
                    length: UInt16(n))
            }
            off += n
        }
        print("Sent")
    }
}

let server = ScreenshotServer()
server.start()
signal(SIGINT) { _ in exit(0) }
RunLoop.main.run()
