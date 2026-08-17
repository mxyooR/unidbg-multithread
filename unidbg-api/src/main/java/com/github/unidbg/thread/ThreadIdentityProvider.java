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

    /** Returns the exact live binding that currently owns the backend. */
    public TaskThreadBinding requireCurrentBinding() {
        TaskThreadBinding binding = currentBinding();
        if (binding == null || !binding.isActive()
                || binding.getGuestThread().getActiveBinding() != binding) {
            throw new IllegalStateException("no live guest thread binding owns the backend");
        }
        return binding;
    }

    /** Fails closed unless the current backend binding belongs to this task. */
    public TaskThreadBinding requireCurrentBinding(Task task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        TaskThreadBinding binding = requireCurrentBinding();
        if (binding.getTask() != task || task.getThreadBinding() != binding) {
            throw new IllegalStateException("current guest thread binding belongs to another task");
        }
        return binding;
    }

    public GuestThreadIncarnation currentGuestThread() {
        TaskThreadBinding binding = currentBinding();
        return binding == null ? null : binding.getGuestThread();
    }

    public GuestThreadIncarnation requireCurrentGuestThread() {
        return requireCurrentBinding().getGuestThread();
    }

    public GuestThreadIncarnation requireCurrentGuestThread(Task task) {
        return requireCurrentBinding(task).getGuestThread();
    }

    public int requireCurrentGuestTid() {
        return requireCurrentGuestThread().getGuestTid();
    }

    public int requireCurrentGuestTid(Task task) {
        return requireCurrentGuestThread(task).getGuestTid();
    }

    /** Legacy compatibility helper. Thread-sensitive runtime paths should fail closed. */
    public int currentGuestTid(int fallback) {
        GuestThreadIncarnation thread = currentGuestThread();
        return thread == null ? fallback : thread.getGuestTid();
    }
}
