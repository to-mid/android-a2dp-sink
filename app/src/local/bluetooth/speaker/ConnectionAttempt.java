package local.bluetooth.speaker;

/** A policy write is not a completed audio connection. */
final class ConnectionAttempt {
    static final int DISCONNECTED = 0, CONNECTING = 1, CONNECTED = 2, DISCONNECTING = 3;

    interface Port {
        int state() throws Exception;
        boolean allowed() throws Exception;
        boolean allow() throws Exception;
        boolean connect() throws Exception;
        long now();
        void pause(long millis) throws Exception;
    }

    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }

    static void establish(Port port) throws Exception {
        int initial = port.state();
        if (initial == CONNECTED) return;
        if (initial == DISCONNECTING) throw new Failure("disconnecting");
        boolean observedProgress = initial == CONNECTING;
        boolean policyMayConnect = false;
        if (!observedProgress) {
            if (port.allowed()) {
                if (!port.connect()) throw new Failure("refused");
            } else {
                if (!port.allow()) throw new Failure("policy-refused");
                // Some services connect as a side effect of enabling the policy;
                // others only save it. Do not send duplicate requests to the first.
                policyMayConnect = true;
            }
        }
        long start = port.now(), stableSince = -1;
        while (port.now() - start < 20000) {
            int state = port.state();
            if (state == CONNECTED) {
                observedProgress = true;
                if (stableSince < 0) stableSince = port.now();
                if (port.now() - stableSince >= 1000) return;
            } else {
                if (stableSince >= 0) throw new Failure("disconnected");
                if (state == CONNECTING) observedProgress = true;
                if (state == DISCONNECTING || state == DISCONNECTED && observedProgress)
                    throw new Failure("disconnected");
                if (policyMayConnect && !observedProgress && port.now() - start >= 1500) {
                    policyMayConnect = false;
                    if (!port.connect()) throw new Failure("refused");
                }
            }
            port.pause(100);
        }
        throw new Failure("timeout");
    }
}
