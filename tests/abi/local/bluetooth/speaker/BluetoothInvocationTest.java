package local.bluetooth.speaker;

import android.content.AttributionSource;
import com.android.modules.utils.SynchronousResultReceiver;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

public final class BluetoothInvocationTest {
    public static final class Direct {
        public List<String> getConnectedDevices() { return Collections.singletonList("sender"); }
        public boolean policy(String device, int policy) { return policy == 100; }
        public boolean policy(int unrelated, String policy) { throw new AssertionError("wrong overload"); }
    }
    public static final class Attributed {
        public boolean policy(String device, int policy, AttributionSource source) {
            if (source == null) throw new AssertionError("missing attribution");
            return false;
        }
    }
    public static final class Async {
        public void policy(String device, int policy, AttributionSource source, SynchronousResultReceiver receiver) {
            if (source == null) throw new AssertionError("missing attribution");
            receiver.send(false);
        }
        public void getConnectedDevices(AttributionSource source, SynchronousResultReceiver receiver) {
            receiver.send(Collections.emptyList());
        }
        public void timeout(AttributionSource source, SynchronousResultReceiver receiver) {}
        public void absent(AttributionSource source, SynchronousResultReceiver receiver) { receiver.send(null); }
    }
    public static final class Unknown {
        public void policy(String device, int policy, long unknown) {}
    }
    public static final class AsyncSource {
        public void getConnectedDevices(SynchronousResultReceiver receiver) {
            throw new AssertionError("deprecated method selected");
        }
        public void getConnectedDevicesWithAttribution(AttributionSource source, SynchronousResultReceiver receiver) {
            if (source == null) throw new AssertionError("missing attribution");
            receiver.send(Collections.singletonList("sender"));
        }
    }
    public static final class ReceiverOnly {
        public void getConnectedDevices(SynchronousResultReceiver receiver) { receiver.send(Collections.emptyList()); }
    }
    private static Object call(Object service, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = BluetoothInvocation.resolve(service.getClass(), name, types);
        return BluetoothInvocation.invoke(service, method, AttributionSource::new, args);
    }
    public static void main(String[] args) throws Exception {
        Class<?>[] policy = {String.class, Integer.class};
        require(Boolean.TRUE.equals(call(new Direct(), "policy", policy, "sender", 100)), "direct policy");
        require(((List<?>) call(new Direct(), "getConnectedDevices", new Class<?>[0])).size() == 1, "direct list");
        require(Boolean.FALSE.equals(call(new Attributed(), "policy", policy, "sender", 100)), "attributed refusal");
        require(Boolean.FALSE.equals(call(new Async(), "policy", policy, "sender", 100)), "async refusal");
        require(((List<?>) call(new Async(), "getConnectedDevices", new Class<?>[0])).isEmpty(), "async empty list");
        require(((List<?>) call(new AsyncSource(), "getConnectedDevices", new Class<?>[0])).size() == 1,
            "source WithAttribution alias");
        require(((List<?>) call(new ReceiverOnly(), "getConnectedDevices", new Class<?>[0])).isEmpty(),
            "receiver without attribution");
        try {
            call(new Async(), "timeout", new Class<?>[0]);
            throw new AssertionError("timeout was hidden");
        } catch (InvocationTargetException error) { require(error.getCause() instanceof TimeoutException, "timeout cause"); }
        try {
            call(new Async(), "absent", new Class<?>[0]);
            throw new AssertionError("missing result was hidden");
        } catch (IllegalStateException expected) {}
        try {
            BluetoothInvocation.resolve(Unknown.class, "policy", policy);
            throw new AssertionError("unknown ABI accepted");
        } catch (NoSuchMethodException expected) {}
        System.out.println("PASS: direct, attribution, receiver, WithAttribution alias, receiver-only, overload matching, refusal, empty list, timeout, absent result, unknown ABI");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
