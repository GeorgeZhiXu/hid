import CoreGraphics
if let event = CGEvent(source: nil) {
    let loc = event.location
    print("\(Int(loc.x)),\(Int(loc.y))")
} else {
    print("0,0")
}
