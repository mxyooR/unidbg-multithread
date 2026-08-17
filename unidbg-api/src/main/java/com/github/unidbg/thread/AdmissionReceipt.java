package com.github.unidbg.thread;

/** Immutable proof that a carrier entered the backend through the dispatcher. */
public final class AdmissionReceipt {

    private final CarrierLease lease;
    private final long invocationId;
    private final long generation;
    private final TaskThreadBinding binding;

    AdmissionReceipt(CarrierLease lease, InvocationRecord invocation,
                     TaskThreadBinding binding) {
        this.lease = lease;
        this.invocationId = invocation.getInvocationId();
        this.generation = invocation.getGeneration();
        this.binding = binding;
    }

    public CarrierLease getLease() {
        return lease;
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

    public GuestThreadIncarnation getGuestThread() {
        return binding.getGuestThread();
    }

    public boolean matches(InvocationRecord invocation) {
        return invocation != null
                && invocationId == invocation.getInvocationId()
                && generation == invocation.getGeneration()
                && lease.getBinding() == binding;
    }
}
