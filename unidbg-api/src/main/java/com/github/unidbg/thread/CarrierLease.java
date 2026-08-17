package com.github.unidbg.thread;

/** Temporary backend ownership. It never owns guest-thread identity. */
public final class CarrierLease {

    private final CarrierLeaseId id;
    private final RunContext runContext;
    private final long invocationId;
    private final long generation;
    private final TaskThreadBinding binding;
    private volatile boolean retired;

    CarrierLease(CarrierLeaseId id, RunContext runContext, long invocationId,
                 long generation, TaskThreadBinding binding) {
        this.id = id;
        this.runContext = runContext;
        this.invocationId = invocationId;
        this.generation = generation;
        this.binding = binding;
    }

    public CarrierLeaseId getId() {
        return id;
    }

    public RunContext getRunContext() {
        return runContext;
    }

    public long getInvocationId() {
        return invocationId;
    }

    public long getGeneration() {
        return generation;
    }

    public TaskThreadBinding getBinding() {
        return binding;
    }

    public boolean isRetired() {
        return retired;
    }

    synchronized void retire() {
        if (retired) {
            return;
        }
        retired = true;
    }
}
