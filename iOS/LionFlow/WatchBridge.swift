import UIKit
import WatchConnectivity

/// Answers LionFlow on the Apple Watch. The watch app has no sign-in of its own: it asks this app, which reads the
/// car and sends the commands (see WatchLink). Without a watch app, or on an iPad, this does nothing.
final class WatchBridge: NSObject, WCSessionDelegate {
    private let link: WatchLink

    init(_ link: WatchLink) {
        self.link = link
        super.init()
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}

    func sessionDidBecomeInactive(_ session: WCSession) {}

    /** After a change of watch the connection has to be started again. */
    func sessionDidDeactivate(_ session: WCSession) { session.activate() }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let path = message["path"] as? String else { return }
        let payload = message["data"] as? String ?? ""
        let link = self.link
        Task { @MainActor in
            // The watch may have started this app in the background just to answer: ask for the time to finish.
            let time = BackgroundTime("LionFlow watch")
            await link.handle(path, payload) { replyPath, answer in
                guard session.isReachable else { return }
                session.sendMessage(["path": replyPath, "data": answer.toString()], replyHandler: nil, errorHandler: { _ in })
            }
            time.end()
        }
    }
}

/** A little time to finish something after the app has left the screen (or was never on it). */
@MainActor
private final class BackgroundTime {
    private var id = UIBackgroundTaskIdentifier.invalid

    init(_ name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            Task { @MainActor in self?.end() }
        }
    }

    func end() {
        if id == .invalid { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
