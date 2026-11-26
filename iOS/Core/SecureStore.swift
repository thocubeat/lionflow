import Foundation
#if canImport(Security)
import Security
#endif

/** Where secrets are kept. The app uses the Keychain; tests use memory. */
protocol SecretBackend: AnyObject {
    func read(_ key: String) -> String?
    func write(_ key: String, _ value: String?)
    func keys() -> [String]
}

final class MemorySecrets: SecretBackend {
    private var map: [String: String] = [:]
    private let lock = NSLock()
    func read(_ key: String) -> String? { lock.lock(); defer { lock.unlock() }; return map[key] }
    func write(_ key: String, _ value: String?) { lock.lock(); map[key] = value; lock.unlock() }
    func keys() -> [String] { lock.lock(); defer { lock.unlock() }; return Array(map.keys) }
}

#if canImport(Security)
/// The iPhone's Keychain: encrypted by the system, with keys held in the Secure Enclave's care. Items can be read
/// after the phone has been unlocked once since it started, so a refresh in the background still works.
final class KeychainSecrets: SecretBackend {
    private let service: String
    private let group: String?

    init(service: String = "no.lionflow.secure", accessGroup: String? = nil) {
        self.service = service
        self.group = accessGroup
    }

    private func base(_ key: String?) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        if let key = key { q[kSecAttrAccount as String] = key }
        if let group = group { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    func read(_ key: String) -> String? {
        var q = base(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ key: String, _ value: String?) {
        let q = base(key)
        guard let value = value else { SecItemDelete(q as CFDictionary); return }
        let data = Data(value.utf8)
        let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = q
            add[kSecValueData as String] = data
            // Readable while the phone is locked (the watch can ask about the car then), and never part of a backup
            // or moved to another phone: on a new phone you sign in again.
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    func keys() -> [String] {
        var q = base(nil)
        q[kSecReturnAttributes as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitAll
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let items = out as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }
    }
}
#endif

/// Small encrypted key/value store for tokens, the one-time-code state and the security code: they never sit on
/// disk in plain text. One set of keys per signed-in account.
final class SecureStore {
    private let backend: SecretBackend
    private let lock = NSRecursiveLock()
    private var current = ""

    init(_ backend: SecretBackend) { self.backend = backend }

    /** Which account's keys are used. "" is the first account; others are stored as "<id>@<name>". */
    var profile: String {
        get { lock.lock(); defer { lock.unlock() }; return current }
        set { lock.lock(); current = newValue; lock.unlock() }
    }

    private func k(_ name: String, _ prof: String) -> String { prof.isEmpty ? name : prof + "@" + name }

    /// `now` is kept from the Android code, where it means "on disk before returning": the Keychain always is.
    func put(_ name: String, _ value: String?, _ prof: String? = nil, now: Bool = false) {
        lock.lock(); defer { lock.unlock() }
        backend.write(k(name, prof ?? current), value)
    }

    func get(_ name: String, _ prof: String? = nil) -> String? {
        lock.lock(); defer { lock.unlock() }
        return backend.read(k(name, prof ?? current))
    }

    /** Removes the active account's keys only. */
    func clear() {
        lock.lock(); defer { lock.unlock() }
        for key in backend.keys() {
            let mine = current.isEmpty ? !key.contains("@") : key.hasPrefix(current + "@")
            if mine { backend.write(key, nil) }
        }
    }

    /** Removes everything, for a fresh install: the Keychain outlives a deleted app, the rest of its data doesn't. */
    func wipeAll() {
        lock.lock(); defer { lock.unlock() }
        for key in backend.keys() { backend.write(key, nil) }
    }
}
