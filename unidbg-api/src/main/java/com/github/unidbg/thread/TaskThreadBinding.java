package com.github.unidbg.thread;

/** Non-owning link between a carrier task and a guest thread incarnation. */
public final class TaskThreadBinding {

    private static long nextBindingId;

    private final long bindingId;
    private final GuestThreadIncarnation guestThread;
    private final Task task;
    private final long epoch;
    private volatile boolean active = true;

    TaskThreadBinding(GuestThreadIncarnation guestThread, Task task, long epoch) {
        synchronized (TaskThreadBinding.class) {
            bindingId = ++nextBindingId;
        }
        this.guestThread = guestThread;
        this.task = task;
        this.epoch = epoch;
    }

    public long getBindingId() {
        return bindingId;
    }

    public GuestThreadIncarnation getGuestThread() {
        return guestThread;
    }

    public RunContext getRunContext() {
        return guestThread.getRunContext();
    }

    public Task getTask() {
        return task;
    }

    public long getEpoch() {
        return epoch;
    }

    public boolean isActive() {
        return active && !guestThread.isRetired();
    }

    void detach() {
        active = false;
    }
}
