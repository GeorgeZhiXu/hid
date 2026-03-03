import Foundation
import IOBluetooth

setbuf(stdout, nil)

// Fixed RFCOMM channel — must match Android app
let kChannel: BluetoothRFCOMMChannelID = 27

class ScreenshotServer: NSObject, IOBluetoothRFCOMMChannelDelegate {

    func rfcommChannelOpenComplete(_ ch: IOBluetoothRFCOMMChannel!, status err: IOReturn) {
        if err == kIOReturnSuccess {
            print("Tablet connected: \(ch.getDevice()?.name ?? "?")")
        } else {
            print("Connection error: \(err)")
        }
    }

    func rfcommChannelData(_ ch: IOBluetoothRFCOMMChannel!,
                           data ptr: UnsafeMutableRawPointer!, length len: Int) {
        guard let ptr = ptr else { return }
        let cmd = String(data: Data(bytes: ptr, count: len), encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        print("Command: '\(cmd)'")
        if cmd == "screenshot" { sendScreenshot(channel: ch) }
    }

    func rfcommChannelClosed(_ ch: IOBluetoothRFCOMMChannel!) {
        print("Tablet disconnected")
    }

    private func sendScreenshot(channel: IOBluetoothRFCOMMChannel) {
        let path = "/tmp/tabletpen-ss.jpg"
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        proc.arguments = ["-x", "-t", "jpg", "-r", path]
        try? proc.run()
        proc.waitUntilExit()

        guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)) else {
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

// Register to listen on our specific RFCOMM channel
class Listener: NSObject {
    let server: ScreenshotServer
    init(server: ScreenshotServer) {
        self.server = server
        super.init()
        // Listen on our fixed channel AND all channels (channel 0 = wildcard)
        IOBluetoothRFCOMMChannel.register(
            forChannelOpenNotifications: self,
            selector: #selector(incoming(_:channel:)),
            withChannelID: kChannel,
            direction: kIOBluetoothUserNotificationChannelDirectionIncoming)
        IOBluetoothRFCOMMChannel.register(
            forChannelOpenNotifications: self,
            selector: #selector(incoming(_:channel:)),
            withChannelID: 0,
            direction: kIOBluetoothUserNotificationChannelDirectionIncoming)
    }
    @objc func incoming(_ n: IOBluetoothUserNotification, channel: IOBluetoothRFCOMMChannel) {
        print("Incoming RFCOMM ch=\(channel.getID()) from \(channel.getDevice()?.name ?? "?")")
        channel.setDelegate(server)
    }
}

let listener = Listener(server: server)
_ = listener

print("Screenshot server listening on RFCOMM channel \(kChannel)")
print("Waiting for tablet to connect...")

signal(SIGINT) { _ in exit(0) }
RunLoop.main.run()
