import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

struct ApiException: Error, LocalizedError, Equatable {
    let code: Int
    let message: String

    init(_ code: Int, _ message: String) { self.code = code; self.message = message }

    /// The brand's server has nothing for this car ("404 / 40400": no status, no trips). Older cars without the
    /// connected box answer this way, and in some countries the server sends the 40400 with another HTTP code.
    var noData: Bool { code == 404 || message.contains("40400") }

    var errorDescription: String? { message }
}

/** The sign-in can't be renewed: the user has to sign in again. */
struct AuthExpiredException: Error {}

enum Http {
    /** Sent to Peugeot and everything else. */
    static let userAgent = "LionFlow/1.0"
    /** MET Norway and OpenStreetMap's address search require a contact in the User-Agent. */
    static let contactAgent = "LionFlow/1.0 (github.com/thocubeat/lionflow)"

    /** No cookies and no cache, like the Android app's client. Tests put their own session here. */
    static var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 90
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    static func request(_ url: String, method: String = "GET", headers: [(String, String)] = [], body: Data? = nil) throws -> URLRequest {
        guard let u = URL(string: url) else { throw ApiException(0, "Bad address: \(url)") }
        var r = URLRequest(url: u)
        r.httpMethod = method
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        r.httpBody = body
        return r
    }

    static func text(_ req: URLRequest) async throws -> String {
        let (data, resp) = try await session.data(for: req)
        let body = String(decoding: data, as: UTF8.self)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code < 200 || code > 299 { throw ApiException(code, describeHttpError(code, body, req.url?.host ?? "")) }
        return body
    }

    static func json(_ req: URLRequest) async throws -> JSONValue? { JSON.parse(try await text(req)) }

    private static let tags = try! NSRegularExpression(pattern: "<[^>]*>")
    private static let spaces = try! NSRegularExpression(pattern: "\\s+")
    private static func replace(_ re: NSRegularExpression, _ s: String, _ with: String) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: with)
    }
    private static func blankToNil(_ s: String) -> String? { s.allSatisfy({ $0.isWhitespace }) ? nil : s }

    /** "Peugeot server error 503 (temporarily unavailable): <the server's own message>" */
    static func describeHttpError(_ code: Int, _ body: String, _ host: String) -> String {
        let who: String
        if host.contains("psa") || host.contains("mpsa") || host.contains("stellantis") || host.contains("peugeot") { who = "Peugeot" }
        else if host.contains("met.no") { who = "MET weather" }
        else if host.contains("nobil") { who = "NOBIL" }
        else { who = host }
        let meaning: String
        switch code {
        case 400: meaning = "request rejected"
        case 401: meaning = "not signed in / session expired"
        case 403: meaning = "access denied"
        case 404: meaning = "not found"
        case 408: meaning = "timed out"
        case 409: meaning = "conflict"
        case 429: meaning = "too many requests — wait a little"
        case 500: meaning = "internal server error"
        case 502: meaning = "bad gateway"
        case 503: meaning = "temporarily unavailable"
        case 504: meaning = "gateway timeout"
        default: meaning = "error"
        }
        // A web server's error page (e.g. nginx "503 Service Temporarily Unavailable") says nothing more than the code.
        let trimmedStart = String(body.drop(while: { $0.isWhitespace }))
        if code >= 500 && trimmedStart.hasPrefix("<") { return "\(who) error \(code) (\(meaning))" }
        var detail: String
        if let j = try? JSONObject(body) {
            var parts: [String] = []
            func add(_ s: String?) { if let s = s, !parts.contains(s) { parts.append(s) } }
            add(blankToNil(j.optString("error_description")))
            add(blankToNil(j.optString("message")))
            if let e = j.optJSONArray("errors")?.optJSONObject(0) {
                let m = e.optString("message")
                add(blankToNil(blankToNil(m) == nil ? e.optString("code") : m))
            }
            add(blankToNil(j.optString("error")))
            if let c = blankToNil(j.optString("code")), c != String(code) { add(c) }
            detail = parts.joined(separator: " · ")
        } else {
            detail = String(replace(spaces, replace(tags, body, " "), " ").trimmingCharacters(in: .whitespacesAndNewlines).prefix(160))
        }
        return "\(who) error \(code) (\(meaning))" + (blankToNil(detail) != nil ? ": \(detail)" : "")
    }

    /** Human text for any error from a network call. */
    static func describe(_ e: Error) -> String {
        if let a = e as? ApiException { return a.message }
        if e is AuthExpiredException { return "Signed out" }
        if let j = e as? JSONError { return j.message }
        if let u = e as? URLError {
            let host = u.failingURL?.host
            switch u.code {
            case .notConnectedToInternet, .cannotFindHost, .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
                return "No internet connection" + (host.map { " (couldn't reach \($0))" } ?? "")
            case .timedOut: return "The server didn't answer in time (timeout)"
            case .cannotConnectToHost, .networkConnectionLost: return "Couldn't connect to the server"
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid, .clientCertificateRejected, .clientCertificateRequired:
                return "Secure connection failed: \(u.localizedDescription)"
            default: return "Network problem: \(u.localizedDescription)"
            }
        }
        if let l = e as? LocalizedError, let d = l.errorDescription { return d }
        return (e as NSError).localizedDescription
    }
}

/** Percent-encoding like Java's `URLEncoder.encode(s, "UTF-8")`: letters, digits and ".-*_" stay, a space becomes "+". */
func urlEncode(_ s: String) -> String {
    var out = ""
    for b in s.utf8 {
        switch b {
        case 0x30...0x39, 0x41...0x5A, 0x61...0x7A, 0x2E, 0x2D, 0x2A, 0x5F: out.append(Character(Unicode.Scalar(b)))
        case 0x20: out.append("+")
        default: out += String(format: "%%%02X", b)
        }
    }
    return out
}
