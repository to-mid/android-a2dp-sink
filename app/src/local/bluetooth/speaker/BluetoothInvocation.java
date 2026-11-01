package local.bluetooth.speaker;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

final class BluetoothInvocation {
    interface Attribution { Object get() throws Exception; }
    private static final String ATTRIBUTION = "android.content.AttributionSource";

    static boolean receiver(Class<?> type) {
        return type.getName().equals("com.android.modules.utils.SynchronousResultReceiver")
            || type.getName().equals("com.android.bluetooth.x.com.android.modules.utils.SynchronousResultReceiver");
    }

    static String abi(Method method, Class<?>... arguments) {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length < arguments.length) return "unsupported";
        for (int i = 0; i < arguments.length; i++) {
            if (!boxed(parameters[i]).isAssignableFrom(boxed(arguments[i]))) return "unsupported";
        }
        int next = arguments.length;
        boolean attributed = next < parameters.length && ATTRIBUTION.equals(parameters[next].getName());
        if (attributed) next++;
        if (next == parameters.length) return attributed ? "attribution" : "direct";
        if (next + 1 == parameters.length && receiver(parameters[next])
                && method.getReturnType() == void.class) return "receiver";
        return "unsupported";
    }

    static Method resolve(Class<?> type, String name, Class<?>... arguments) throws NoSuchMethodException {
        // Android 13 A2DP source retains deprecated names alongside attributed methods.
        for (String candidate : new String[]{name + "WithAttribution", name}) {
            Method selected = null;
            for (Method method : type.getMethods()) {
                if (!method.getName().equals(candidate) || "unsupported".equals(abi(method, arguments))) continue;
                if (selected != null) throw new NoSuchMethodException("Ambiguous Bluetooth method: " + candidate);
                selected = method;
            }
            if (selected != null) return selected;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    static Object invoke(Object service, Method method, Attribution attribution, Object... args) throws Exception {
        Class<?>[] parameters = method.getParameterTypes();
        Object[] values = Arrays.copyOf(args, parameters.length);
        int next = args.length;
        if (next < parameters.length && ATTRIBUTION.equals(parameters[next].getName())) values[next++] = attribution.get();
        if (next == parameters.length) return method.invoke(service, values);
        Class<?> receiverType = parameters[next];
        Object receiver = receiverType.getMethod("get").invoke(null);
        values[next] = receiver;
        method.invoke(service, values);
        Object result = receiverType.getMethod("awaitResultNoInterrupt", Duration.class)
            .invoke(receiver, Duration.ofSeconds(5));
        // Never turn an IPC timeout or an absent result into a successful operation.
        Object value = result.getClass().getMethod("getValue", Object.class).invoke(result, new Object[]{null});
        if (value == null) throw new IllegalStateException("Bluetooth returned no result: " + method.getName());
        return value;
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }
}
