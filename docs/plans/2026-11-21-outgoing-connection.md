# Phone-initiated audio connection

The existing logs show an outgoing A2DP sink attempt to TO_MID ending in
BTA_AV_OPEN_EVT::FAILED status 2. This is separate from the AVRCP NULL-list
Bluetooth process crash. Cached device metadata does not contain AudioSource
for this Windows peer. Windows-initiated playback has worked, but this does
not demonstrate successful SDP discovery or phone-initiated connection.

Previously RootCommand only wrote CONNECTION_POLICY_ALLOWED and treated its
boolean result as successful connection. Reference Android 11 A2dpSinkService
returns true for the policy write even when its internal connect request fails.
That reference is not an exact source match to the shipped MIUI service:
https://github.com/LineageOS/android_packages_apps_Bluetooth/blob/lineage-18.1/src/com/android/bluetooth/a2dpsink/A2dpSinkService.java

The revised path refreshes remote services using an address-filtered ACTION_UUID
response on a dedicated HandlerThread (RootCommand's main Looper does not run).
Discovery is bounded to 12 seconds; a failure or unavailable API does not block
a profile connection or imply that the peer lacks a service. Only the actual
response, not old cached UUIDs, is used to explain missing audio services.

For an allowed peer the code explicitly requests connect. When first enabling
the policy it observes the service's automatic attempt before considering one
explicit request, avoiding duplicate requests on Android 11 services that connect
as a policy side effect. Existing connected/connecting sessions are preserved.
The command reports success only after the connection remains connected for one
second. State loss, refusal and the bounded 20-second wait return distinct errors.
It does not retry a failed connection or restart Bluetooth in the background.

These changes correct request handling and stale service discovery, and improve
failure reporting. They do not manufacture an AudioSource SDP record on Windows
or bypass a remote service-discovery failure. Whether refreshed discovery fixes
the affected peer is unverified until the phone is available again. No further
device access is authorized while the user has taken the phone away.

Host regression coverage exercises policy auto-connect/save-only services,
explicit requests for an allowed peer, existing sessions, request refusal,
immediate state loss, one-second stability and timeout without real sleeping.
Physical validation still needs outgoing Win11 connection in receiver mode,
outgoing headphones in sender mode, and switching back to receiver then playing.
