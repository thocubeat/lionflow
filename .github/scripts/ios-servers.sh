#!/bin/bash
# Can an iPhone talk to the servers LionFlow uses? iOS only lets an app use encrypted connections of a certain
# standard ("App Transport Security"), and a server below it would simply not work on a real phone. This asks each
# server the way iOS would. Nothing is signed in to and nothing is sent: it is the greeting every connection starts with.
set -u
tmp="$(mktemp -d)"
n=0
for url in https://api.groupe-psa.com https://otp.mpsa.com \
           https://idpcvs.peugeot.com https://idpcvs.citroen.com https://idpcvs.driveds.com https://idpcvs.opel.com https://idpcvs.vauxhall.co.uk \
           https://api.met.no https://overpass-api.de https://nominatim.openstreetmap.org; do
  n=$((n + 1))
  (
    out="$(nscurl --ats-diagnostics "$url" 2>&1)"
    result="$(printf '%s\n' "$out" | awk '/ATS Default Connection/{f=1} f && /Result/{print $3; exit}')"
    echo "$url: ${result:-NO ANSWER}" > "$tmp/$n.txt"
  ) &
done
wait
echo "With iOS' own rules for encrypted connections (PASS = an iPhone app can use this server as it is):"
cat "$tmp"/*.txt

# Remote control goes over another kind of connection (MQTT), to another server and port.
cat > "$tmp/mqtt.swift" <<'SWIFT'
import Foundation
import Network

let connection = NWConnection(host: "mwa.mpsa.com", port: 8885, using: .tls)
let done = DispatchSemaphore(value: 0)
connection.stateUpdateHandler = { state in
    switch state {
    case .ready:
        print("mwa.mpsa.com:8885 (remote control): PASS, an encrypted connection with Apple's network code")
        done.signal()
    case .failed(let error), .waiting(let error):
        print("mwa.mpsa.com:8885 (remote control): FAIL, \(error)")
        done.signal()
    default:
        break
    }
}
connection.start(queue: .global())
if done.wait(timeout: .now() + 25) == .timedOut { print("mwa.mpsa.com:8885 (remote control): NO ANSWER in 25 seconds") }
connection.cancel()
SWIFT
swift "$tmp/mqtt.swift" 2>&1 | tail -n 3
echo | openssl s_client -connect mwa.mpsa.com:8885 -servername mwa.mpsa.com 2>/dev/null | grep -E "Protocol|Cipher is|Verify return code" | head -n 4
exit 0
