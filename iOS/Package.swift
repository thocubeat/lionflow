// swift-tools-version:5.9
import PackageDescription

// The app itself is an Xcode project (see project.yml). This package only exists so the car logic in Core/ can be
// built and tested on its own with `swift test`, on a Mac, without an iPhone simulator.
let package = Package(
    name: "LionFlowCore",
    platforms: [.iOS(.v15), .macOS(.v12), .watchOS(.v8)],
    products: [.library(name: "LionFlowCore", targets: ["LionFlowCore"])],
    targets: [
        .target(name: "LionFlowCore", path: "Core"),
        .testTarget(name: "LionFlowCoreTests", dependencies: ["LionFlowCore"], path: "CoreTests", resources: [.copy("Fixtures")]),
    ]
)
