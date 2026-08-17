package com.github.unidbg.thread;

/** Resolves thread-sensitive APIs from the dispatcher-owned current binding. */
public final class ThreadIdentityProvider {

    private final ThreadDispatcher dispatcher;

    public ThreadIdentityProvider(ThreadDispatcher dispatcher) {
        if (dispatcher == null) {
            throw new NullPointerException("dispatcher");
        }
        this.dispatcher = dispatcher;
    }

    public TaskThreadBinding currentBinding() {
        return dispatcher.getRunningThreadBinding();
    }

    public GuestThreadIncarnation currentGuestThread() {
        TaskThreadBinding binding = currentBinding();
        return binding == null ? null : binding.getGuestThread();
    }

    public GuestThreadIncarnation requireCurrentGuestThread() {
        GuestThreadIncarnation thread = currentGuestThread();
        if (thread == null) {
            throw new IllegalStateException("no guest thread owns the backend");
        }
        return thread;
    }

    public int currentGuestTid(int fallback) {
        GuestThreadIncarnation thread = currentGuestThread();
        return thread == null ? fallback : thread.getGuestTid();
    }
}
