import Foundation
import Network

/// The few kinds of MQTT 3.1.1 packets remote control needs, as bytes.
/// (The Android app uses the Eclipse Paho library for this; here it is written out, to stay without dependencies.)
enum MqttCodec {
    struct Packet: Equatable {
        var type: UInt8
        var flags: UInt8
        var body: [UInt8]
    }

    /** The "remaining length" of a packet: 7 bits at a time, lowest first. */
    static func length(_ n: Int) -> [UInt8] {
        var out: [UInt8] = []
        var x = n
        repeat {
            var b = UInt8(x % 128)
            x /= 128
            if x > 0 { b |= 0x80 }
            out.append(b)
        } while x > 0
        return out
    }

    static func string(_ s: String) -> [UInt8] {
        let b = Array(s.utf8)
        return [UInt8(b.count >> 8), UInt8(b.count & 0xff)] + b
    }

    private static func packet(_ first: UInt8, _ body: [UInt8]) -> [UInt8] { [first] + length(body.count) + body }

    static func connect(clientId: String, username: String, password: String, keepAlive: Int, cleanSession: Bool = true) -> [UInt8] {
        var body = string("MQTT")
        body.append(4)                                              // protocol level: 3.1.1
        body.append(0x80 | 0x40 | (cleanSession ? 0x02 : 0x00))     // user name, password, clean session
        body.append(UInt8((keepAlive >> 8) & 0xff))
        body.append(UInt8(keepAlive & 0xff))
        body += string(clientId) + string(username) + string(password)
        return packet(0x10, body)
    }

    static func subscribe(packetId: UInt16, topic: String, qos: UInt8 = 0) -> [UInt8] {
        packet(0x82, [UInt8(packetId >> 8), UInt8(packetId & 0xff)] + string(topic) + [qos])
    }

    /** A message that isn't confirmed (QoS 0), as the Android app sends them. */
    static func publish(topic: String, payload: [UInt8]) -> [UInt8] { packet(0x30, string(topic) + payload) }

    static let pingreq: [UInt8] = [0xC0, 0x00]
    static let disconnect: [UInt8] = [0xE0, 0x00]

    /// Takes one whole packet off the front of what has arrived so far; nil when more bytes are needed.
    /// Throws when the bytes can't be MQTT.
    static func take(_ buffer: inout [UInt8]) throws -> Packet? {
        if buffer.count < 2 { return nil }
        var len = 0
        var shift = 0
        var i = 1
        while true {
            if i >= buffer.count { return nil }
            let b = buffer[i]
            len |= Int(b & 0x7f) << shift
            i += 1
            if b & 0x80 == 0 { break }
            shift += 7
            if shift > 21 { throw MqttClient.MqttError(reasonCode: 0, message: "Not an MQTT answer") }
        }
        if buffer.count < i + len { return nil }
        let p = Packet(type: buffer[0] >> 4, flags: buffer[0] & 0x0f, body: Array(buffer[i..<(i + len)]))
        buffer.removeFirst(i + len)
        return p
    }

    /** The topic and the message of a PUBLISH packet. */
    static func message(_ p: Packet) -> (String, [UInt8])? {
        guard p.type == 3, p.body.count >= 2 else { return nil }
        let n = Int(p.body[0]) << 8 | Int(p.body[1])
        var at = 2 + n
        guard p.body.count >= at else { return nil }
        let topic = String(decoding: p.body[2..<at], as: UTF8.self)
        // With QoS 1 or 2 a packet number comes before the message.
        if (p.flags >> 1) & 3 > 0 { at += 2 }
        guard p.body.count >= at else { return nil }
        return (topic, Array(p.body[at...]))
    }
}

/// A small MQTT client over TLS: connect with a user name and password, listen to one topic tree, send messages.
final class MqttClient: @unchecked Sendable {
    /** `reasonCode` follows the Android library's numbers: 4 and 5 = login refused, 128 = not allowed to listen. */
    struct MqttError: Error, LocalizedError, Equatable {
        var reasonCode: Int
        var message: String
        var errorDescription: String? { message }
    }

    private let queue = DispatchQueue(label: "no.lionflow.mqtt")
    private var connection: NWConnection?
    private var buffer: [UInt8] = []
    private let flag = NSLock()
    private var connectedFlag = false
    /** Set on the client's queue, read from anywhere. */
    private var connected: Bool {
        get { flag.lock(); defer { flag.unlock() }; return connectedFlag }
        set { flag.lock(); connectedFlag = newValue; flag.unlock() }
    }
    private var connack: ((Result<Void, Error>) -> Void)?
    private var subacks: [UInt16: (Result<Void, Error>) -> Void] = [:]
    private var nextId: UInt16 = 1
    private var ping: DispatchSourceTimer?

    /** Called (on the client's own queue) for every message that arrives. */
    var onMessage: ((String, [UInt8]) -> Void)?

    var isConnected: Bool { connected }

    func connect(host: String, port: UInt16, tls: Bool, username: String, password: String, keepAlive: Int = 120, timeoutSeconds: Int = 20) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            queue.async {
                guard let p = NWEndpoint.Port(rawValue: port) else {
                    c.resume(throwing: MqttError(reasonCode: 0, message: "Bad port"))
                    return
                }
                let conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: tls ? .tls : .tcp)
                self.connection = conn
                self.buffer = []
                self.connack = { result in c.resume(with: result) }
                conn.stateUpdateHandler = { [weak self] state in
                    guard let self = self else { return }
                    switch state {
                    case .ready:
                        // An empty client id: the broker gives one itself, as it does for the carmakers' own apps.
                        self.sendNow(MqttCodec.connect(clientId: "", username: username, password: password, keepAlive: keepAlive))
                        self.receive(conn)
                    case .failed(let e):
                        self.closed(MqttError(reasonCode: 0, message: e.localizedDescription))
                    case .waiting(let e):
                        // No route, or the server refuses: don't wait for it here.
                        self.closed(MqttError(reasonCode: 0, message: e.localizedDescription))
                        conn.cancel()
                    case .cancelled:
                        self.closed(MqttError(reasonCode: 0, message: "Connection closed"))
                    default:
                        break
                    }
                }
                conn.start(queue: self.queue)
                self.queue.asyncAfter(deadline: .now() + .seconds(timeoutSeconds)) { [weak self] in
                    guard let self = self, self.connack != nil else { return }
                    self.closed(MqttError(reasonCode: 32000, message: "Timed out waiting for the server"))
                    conn.cancel()
                }
                // Keep the connection alive while it is idle.
                let t = DispatchSource.makeTimerSource(queue: self.queue)
                let every = max(10, keepAlive / 2)
                t.schedule(deadline: .now() + .seconds(every), repeating: .seconds(every))
                t.setEventHandler { [weak self] in
                    guard let self = self, self.connected else { return }
                    self.sendNow(MqttCodec.pingreq)
                }
                t.resume()
                self.ping = t
            }
        }
    }

    /** Listens to a topic (QoS 0). Throws with code 128 when the broker says this account may not. */
    func subscribe(_ topic: String, timeoutSeconds: Int = 20) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            queue.async {
                guard self.connected else {
                    c.resume(throwing: MqttError(reasonCode: 32104, message: "Not connected"))
                    return
                }
                let id = self.nextId
                self.nextId = self.nextId == UInt16.max ? 1 : self.nextId + 1
                self.subacks[id] = { result in c.resume(with: result) }
                self.sendNow(MqttCodec.subscribe(packetId: id, topic: topic))
                self.queue.asyncAfter(deadline: .now() + .seconds(timeoutSeconds)) { [weak self] in
                    self?.subacks.removeValue(forKey: id)?(.failure(MqttError(reasonCode: 32000, message: "Timed out waiting for the server")))
                }
            }
        }
    }

    func publish(_ topic: String, _ payload: [UInt8]) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            queue.async {
                guard self.connected, let conn = self.connection else {
                    c.resume(throwing: MqttError(reasonCode: 32104, message: "Not connected"))
                    return
                }
                conn.send(content: Data(MqttCodec.publish(topic: topic, payload: payload)), completion: .contentProcessed { error in
                    if let e = error { c.resume(throwing: MqttError(reasonCode: 0, message: e.localizedDescription)) } else { c.resume() }
                })
            }
        }
    }

    func disconnect() {
        queue.async {
            guard let conn = self.connection else { return }
            if self.connected { conn.send(content: Data(MqttCodec.disconnect), completion: .contentProcessed { _ in conn.cancel() }) } else { conn.cancel() }
            self.closed(MqttError(reasonCode: 0, message: "Connection closed"))
        }
    }

    // Everything below runs on the queue.

    private func sendNow(_ bytes: [UInt8]) {
        connection?.send(content: Data(bytes), completion: .contentProcessed { _ in })
    }

    private func closed(_ error: Error) {
        connected = false
        ping?.cancel()
        ping = nil
        if let waiting = connack {
            connack = nil
            waiting(.failure(error))
        }
        let waiters = subacks
        subacks = [:]
        for w in waiters.values { w(.failure(error)) }
    }

    private func receive(_ conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let d = data, !d.isEmpty {
                self.buffer.append(contentsOf: d)
                do {
                    while let p = try MqttCodec.take(&self.buffer) { self.handle(p) }
                } catch {
                    self.closed(error)
                    conn.cancel()
                    return
                }
            }
            if let e = error {
                self.closed(MqttError(reasonCode: 0, message: e.localizedDescription))
                return
            }
            if isComplete {
                self.closed(MqttError(reasonCode: 0, message: "Connection closed"))
                conn.cancel()
                return
            }
            self.receive(conn)
        }
    }

    private func handle(_ p: MqttCodec.Packet) {
        switch p.type {
        case 2:     // CONNACK
            let code = p.body.count >= 2 ? Int(p.body[1]) : 0
            let waiting = connack
            connack = nil
            if code == 0 {
                connected = true
                waiting?(.success(()))
            } else {
                waiting?(.failure(MqttError(reasonCode: code, message: code == 4 || code == 5 ? "Not authorized to connect" : "Connection refused")))
                connection?.cancel()
            }
        case 9:     // SUBACK
            guard p.body.count >= 3 else { return }
            let id = UInt16(p.body[0]) << 8 | UInt16(p.body[1])
            let waiting = subacks.removeValue(forKey: id)
            if p.body[2] == 0x80 { waiting?(.failure(MqttError(reasonCode: 128, message: "The server refused the subscription"))) } else { waiting?(.success(())) }
        case 3:     // PUBLISH
            if let m = MqttCodec.message(p) { onMessage?(m.0, m.1) }
        default:
            break   // PINGRESP and anything else
        }
    }
}
