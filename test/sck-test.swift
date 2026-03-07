// Minimal SCK callback test — verifies ScreenCaptureKit delivers frames
// Build: swiftc -framework ScreenCaptureKit -framework CoreMedia -framework CoreVideo test/sck-test.swift -o test/sck-test
// Run: ./test/sck-test

import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo

@available(macOS 12.3, *)
class SCKTest: NSObject, SCStreamOutput, SCStreamDelegate {
    var stream: SCStream?
    var frameCount = 0
    var done = false

    func start() async throws {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else {
            print("FAIL: No display found")
            return
        }
        print("Display: \(display.width)x\(display.height)")

        let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
        let config = SCStreamConfiguration()
        config.width = display.width
        config.height = display.height
        config.minimumFrameInterval = CMTime(value: 1, timescale: 30)
        config.queueDepth = 8
        config.showsCursor = true
        config.pixelFormat = kCVPixelFormatType_32BGRA

        stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream!.addStreamOutput(self, type: .screen, sampleHandlerQueue: .global(qos: .userInteractive))
        try await stream!.startCapture()
        print("SCK stream started (queueDepth=8)")
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .screen else { return }
        frameCount += 1
        if frameCount <= 5 || frameCount % 30 == 0 {
            let buf = CMSampleBufferGetImageBuffer(sampleBuffer)
            let w = buf.map { CVPixelBufferGetWidth($0) } ?? 0
            let h = buf.map { CVPixelBufferGetHeight($0) } ?? 0
            print("  Frame #\(frameCount): \(w)x\(h)")
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("FAIL: SCK stream stopped with error: \(error.localizedDescription)")
        done = true
    }
}

if #available(macOS 12.3, *) {
    let test = SCKTest()
    Task {
        do {
            try await test.start()
        } catch {
            print("FAIL: \(error.localizedDescription)")
            test.done = true
        }
    }

    print("Waiting 10s for frames (move mouse/drag windows to trigger)...")
    for i in 1...10 {
        Thread.sleep(forTimeInterval: 1)
        print("  \(i)s: \(test.frameCount) frames")
        if test.done { break }
    }

    if test.frameCount >= 10 {
        print("PASS: SCK delivered \(test.frameCount) frames in 10s")
    } else if test.frameCount > 0 {
        print("WARN: SCK delivered only \(test.frameCount) frames in 10s (try dragging windows)")
    } else {
        print("FAIL: SCK delivered 0 frames — check Screen Recording permission")
    }
} else {
    print("FAIL: macOS 12.3+ required")
}
