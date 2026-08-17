package com.github.unidbg.thread;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable guest-thread state that must survive invocation carrier switches. */
public final class GuestThreadExecutionState {

    public enum State {
        ALIVE,
        RETIRING,
        RETIRED
    }

    private final Map<String, Object> threadLocal = new LinkedHashMap<>();
    private final Map<Object, Object> attachments = new LinkedHashMap<>();
    private volatile State state = State.ALIVE;
    private volatile int errno;
    private volatile Object pendingException;

    public synchronized State getState() {
        return state;
    }

    synchronized void beginRetirement() {
        if (state == State.ALIVE) {
            state = State.RETIRING;
        }
    }

    synchronized void retire() {
        state = State.RETIRED;
        pendingException = null;
        threadLocal.clear();
        attachments.clear();
    }

    public int getErrno() {
        return errno;
    }

    public void setErrno(int errno) {
        this.errno = errno;
    }

    public synchronized Object getPendingException() {
        return pendingException;
    }

    public synchronized void setPendingException(Object exception) {
        ensureLive();
        pendingException = exception;
    }

    public synchronized void clearPendingException() {
        ensureLive();
        pendingException = null;
    }

    public synchronized void putThreadLocal(String key, Object value) {
        ensureLive();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("thread-local key must not be blank");
        }
        threadLocal.put(key, value);
    }

    public synchronized Object getThreadLocal(String key) {
        return threadLocal.get(key);
    }

    public synchronized Object removeThreadLocal(String key) {
        return threadLocal.remove(key);
    }

    public synchronized Map<String, Object> getThreadLocalSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(threadLocal));
    }

    public synchronized Object getAttachment(Object key) {
        return attachments.get(key);
    }

    public synchronized Object putAttachmentIfAbsent(Object key, Object value) {
        ensureLive();
        if (key == null || value == null) {
            throw new NullPointerException("attachment key and value must not be null");
        }
        Object existing = attachments.get(key);
        if (existing != null) {
            return existing;
        }
        attachments.put(key, value);
        return value;
    }

    public synchronized Object removeAttachment(Object key) {
        return attachments.remove(key);
    }

    private void ensureLive() {
        if (state == State.RETIRED) {
            throw new IllegalStateException("guest thread state is retired");
        }
    }
}
