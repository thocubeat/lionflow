import XCTest
@testable import LionFlowCore

final class JSONTests: XCTestCase {
    func testReadsLikeAndroid() throws {
        let j = try JSONObject("{\"a\":12,\"b\":12.5,\"c\":\"7\",\"d\":null,\"e\":true,\"f\":{\"g\":[1,{\"h\":\"x\"}]},\"s\":\"te\\u00e9\\n\"}")
        XCTAssertEqual(j.optString("a"), "12")
        XCTAssertEqual(j.optString("b"), "12.5")
        XCTAssertEqual(j.optDouble("c"), 7)
        XCTAssertEqual(j.optInt("c"), 7)
        XCTAssertEqual(j.optString("d"), "null")      // Android gives the text "null" for a JSON null
        XCTAssertNil(j.str("d"))
        XCTAssertTrue(j.has("d"))
        XCTAssertTrue(j.isNull("d"))
        XCTAssertTrue(j.isNull("missing"))
        XCTAssertTrue(j.optDouble("missing").isNaN)
        XCTAssertNil(j.num("missing"))
        XCTAssertEqual(j.optString("e"), "true")
        XCTAssertEqual(j.obj("f")?.optJSONArray("g")?.objects().first?.optString("h"), "x")
        XCTAssertEqual(j.optString("s"), "teé\n")
        XCTAssertEqual(j.keys(), ["a", "b", "c", "d", "e", "f", "s"])
    }

    func testWritesLikeAndroid() {
        let j = JSONObject().put("a", 1).put("b", 2.0).put("c", 2.5).put("d", "x/y\"z").put("e", JSONArray().put(true).put(JSONValue.null))
        XCTAssertEqual(j.toString(), "{\"a\":1,\"b\":2,\"c\":2.5,\"d\":\"x\\/y\\\"z\",\"e\":[true,null]}")
        XCTAssertEqual(JSONValue.javaDouble(12), "12.0")
        XCTAssertEqual(JSONValue.javaDouble(0.0001), "1.0E-4")
        XCTAssertEqual(JSONValue.javaDouble(12345678), "1.2345678E7")
        XCTAssertEqual(JSONValue.javaDouble(-0.5), "-0.5")
    }
}
