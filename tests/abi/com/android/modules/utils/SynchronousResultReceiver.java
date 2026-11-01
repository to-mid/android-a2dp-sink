package com.android.modules.utils;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

public final class SynchronousResultReceiver {
    private Object value;
    private boolean received;
    public static SynchronousResultReceiver get() { return new SynchronousResultReceiver(); }
    public void send(Object result) { value = result; received = true; }
    public Result awaitResultNoInterrupt(Duration timeout) throws TimeoutException {
        if (!received) throw new TimeoutException("test timeout");
        return new Result(value);
    }
    public static final class Result {
        private final Object value;
        Result(Object result) { value = result; }
        public Object getValue(Object fallback) { return value == null ? fallback : value; }
    }
}
