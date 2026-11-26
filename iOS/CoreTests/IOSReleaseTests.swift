import XCTest
@testable import LionFlowCore

/// New builds and the app's signature (IOSRelease, AppSignature). Fixtures/ios_source_sample.json was written by
/// .github/scripts/ios-source.py from a real build, so the app is tested on the file the build machine publishes.
final class IOSReleaseTests: XCTestCase {
    private let fixtures = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures")

    func testTheNewestBuildIsReadFromThePublishedSource() throws {
        let text = try String(contentsOf: fixtures.appendingPathComponent("ios_source_sample.json"), encoding: .utf8)
        let b = try XCTUnwrap(IOSRelease.latest(inSource: text))
        XCTAssertEqual(b.version, "0.1.0")
        XCTAssertEqual(b.build, 19)
        XCTAssertTrue(b.downloadURL.hasPrefix("https://github.com/thocubeat/lionflow/releases/download/ios-latest/"))
        XCTAssertEqual(b.notes, "iOS: a test")
    }

    func testTheFirstVersionListedIsTheNewestAndLionFlowIsFoundAmongOtherApps() {
        let text = """
        {"name": "Mixed", "apps": [
          {"bundleIdentifier": "com.other.app", "versions": [{"version": "9.9", "downloadURL": "https://x/other.ipa"}]},
          {"bundleIdentifier": "no.lionflow.app", "versions": [
            {"version": "0.1.60", "buildVersion": "60", "downloadURL": "https://x/60.ipa", "localizedDescription": "Newest"},
            {"version": "0.1.59", "buildVersion": "59", "downloadURL": "https://x/59.ipa"}]}]}
        """
        XCTAssertEqual(IOSRelease.latest(inSource: text), IOSRelease.Build(version: "0.1.60", build: 60, downloadURL: "https://x/60.ipa", notes: "Newest"))
    }

    func testNothingIsMadeUpFromABrokenOrEmptySource() {
        XCTAssertNil(IOSRelease.latest(inSource: ""))
        XCTAssertNil(IOSRelease.latest(inSource: "<html>Not Found</html>"))
        XCTAssertNil(IOSRelease.latest(inSource: #"{"apps": []}"#))
        XCTAssertNil(IOSRelease.latest(inSource: #"{"apps": [{"bundleIdentifier": "no.lionflow.app", "versions": []}]}"#))
        XCTAssertNil(IOSRelease.latest(inSource: #"{"apps": [{"bundleIdentifier": "no.lionflow.app", "versions": [{"version": "0.1.2"}]}]}"#))
    }

    func testWhichBuildIsNewer() {
        func b(_ v: String, _ n: Int) -> IOSRelease.Build { IOSRelease.Build(version: v, build: n, downloadURL: "u", notes: "") }
        XCTAssertTrue(IOSRelease.isNewer(b("0.1.25", 25), thanVersion: "0.1.24", build: "24"))
        XCTAssertFalse(IOSRelease.isNewer(b("0.1.25", 25), thanVersion: "0.1.25", build: "25"))
        XCTAssertFalse(IOSRelease.isNewer(b("0.1.24", 24), thanVersion: "0.1.25", build: "25"))
        // Number by number, not as text: 0.1.100 is newer than 0.1.99.
        XCTAssertTrue(IOSRelease.isNewer(b("0.1.100", 100), thanVersion: "0.1.99", build: "99"))
        XCTAssertTrue(IOSRelease.isNewer(b("0.2.3", 3), thanVersion: "0.1.99", build: "99"))
        XCTAssertTrue(IOSRelease.isNewer(b("1.0", 1), thanVersion: "0.9.9", build: "500"))
        // The same version: the build number decides.
        XCTAssertTrue(IOSRelease.isNewer(b("0.1.0", 20), thanVersion: "0.1.0", build: "19"))
        XCTAssertFalse(IOSRelease.isNewer(b("0.1.0", 19), thanVersion: "0.1", build: "19"))
    }

    func testTheLinksHandTheWholeAddressToSideStoreAndAltStore() throws {
        let file = "https://github.com/thocubeat/lionflow/releases/download/ios-latest/LionFlow-0.1.60.ipa?a=1&b=2"
        let links = IOSRelease.installLinks(file)
        XCTAssertEqual(links.map { $0.scheme }, ["sidestore", "altstore"])
        for link in links {
            // How both tools read the link: the host says what to do, the "url" item is the file.
            let parts = try XCTUnwrap(URLComponents(url: link, resolvingAgainstBaseURL: false))
            XCTAssertEqual(parts.host, "install")
            XCTAssertEqual(parts.queryItems?.count, 1)
            XCTAssertEqual(parts.queryItems?.first?.name, "url")
            XCTAssertEqual(parts.queryItems?.first?.value, file)
        }
        XCTAssertEqual(IOSRelease.toolLinks.map { $0.absoluteString }, ["sidestore://", "altstore://"])
    }

    func testTheSignatureRunsOutWhenTheProfileSays() throws {
        // A provisioning profile is a signed file (binary) with a property list in the middle.
        let plist = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>AppIDName</key><string>XC no lionflow app</string>
            <key>CreationDate</key><date>2026-11-26T10:00:00Z</date>
            <key>ExpirationDate</key><date>2026-12-03T10:00:00Z</date>
            <key>TimeToLive</key><integer>7</integer>
        </dict>
        </plist>
        """
        var data = Data([0x30, 0x82, 0x4e, 0x1a, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x07, 0x02, 0xa0])
        data.append(Data(plist.utf8))
        data.append(Data([0xa0, 0x82, 0x0b, 0x3f, 0x30, 0x82, 0x04, 0x00, 0x00, 0xff]))
        let expiry = try XCTUnwrap(AppSignature.expiry(profile: data))
        XCTAssertEqual(expiry, ISO8601DateFormatter().date(from: "2026-12-03T10:00:00Z"))
    }

    func testNoProfileOrABrokenOneGivesNoDate() {
        XCTAssertNil(AppSignature.expiry(profile: Data()))
        XCTAssertNil(AppSignature.expiry(profile: Data("no property list here".utf8)))
        XCTAssertNil(AppSignature.expiry(profile: Data("<?xml version=\"1.0\"?><plist><dict><key>ExpirationDate</key>".utf8)))
        XCTAssertNil(AppSignature.expiry(profile: Data("<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>Name</key><string>x</string></dict></plist>".utf8)))
    }
}
