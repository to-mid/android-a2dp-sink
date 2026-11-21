package local.bluetooth.speaker;

public final class ConnectionAttemptTest {
    static final class Fake implements ConnectionAttempt.Port {
        long time;
        int connects, policies;
        boolean allowed = true, acceptConnect = true, acceptPolicy = true;
        int[] timeline = {0, 0};
        Fake script(int... values) { timeline = values; return this; }
        public int state() {
            int value = 0;
            for (int i = 0; i < timeline.length; i += 2)
                if (time >= timeline[i]) value = timeline[i + 1];
            return value;
        }
        public boolean allowed() { return allowed; }
        public boolean allow() { policies++; return acceptPolicy; }
        public boolean connect() { connects++; return acceptConnect; }
        public long now() { return time; }
        public void pause(long millis) { time += millis; }
    }

    public static void main(String[] args) throws Exception {
        Fake already = new Fake().script(0, 2);
        ConnectionAttempt.establish(already);
        require(already.connects == 0 && already.policies == 0, "already connected was disturbed");

        Fake explicit = new Fake().script(0, 0, 200, 1, 600, 2);
        ConnectionAttempt.establish(explicit);
        require(explicit.connects == 1 && explicit.policies == 0 && explicit.time >= 1600,
            "allowed device needs an explicit request and stable connection");

        Fake auto = new Fake().script(0, 0, 100, 1, 600, 2);
        auto.allowed = false;
        ConnectionAttempt.establish(auto);
        require(auto.policies == 1 && auto.connects == 0, "policy auto-connect duplicated");

        Fake saveOnly = new Fake().script(0, 0, 1700, 1, 2000, 2);
        saveOnly.allowed = false;
        ConnectionAttempt.establish(saveOnly);
        require(saveOnly.policies == 1 && saveOnly.connects == 1, "save-only service never connected");

        Fake pending = new Fake().script(0, 1, 500, 2);
        ConnectionAttempt.establish(pending);
        require(pending.connects == 0 && pending.policies == 0, "pending attempt duplicated");

        Fake policyFailure = new Fake().script(0, 0, 100, 1, 400, 0);
        policyFailure.allowed = false;
        fails(policyFailure, "disconnected");
        require(policyFailure.connects == 0, "failed auto-connect retried indefinitely");
        fails(new Fake().script(0, 1, 500, 0), "disconnected");
        fails(new Fake().script(0, 0, 100, 1, 300, 2, 600, 0), "disconnected");
        fails(new Fake().script(0, 3), "disconnecting");

        Fake refused = new Fake();
        refused.acceptConnect = false;
        fails(refused, "refused");
        Fake policyRefused = new Fake();
        policyRefused.allowed = false;
        policyRefused.acceptPolicy = false;
        fails(policyRefused, "policy-refused");
        Fake timeout = new Fake();
        fails(timeout, "timeout");
        require(timeout.time == 20000 && timeout.connects == 1, "unbounded connection wait");
        System.out.println("PASS: actual connection, policy auto-connect/save-only, existing sessions, refusal, disconnect, stability, bounded timeout");
    }

    static void fails(Fake fake, String code) throws Exception {
        try {
            ConnectionAttempt.establish(fake);
            throw new AssertionError("failed connection reported success: " + code);
        } catch (ConnectionAttempt.Failure expected) {
            require(code.equals(expected.code), "expected " + code + ", got " + expected.code);
        }
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
