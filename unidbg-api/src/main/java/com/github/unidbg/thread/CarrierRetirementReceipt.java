package com.github.unidbg.thread;

/** Immutable proof that a previously admitted carrier released the backend. */
public final class CarrierRetirementReceipt {

    private final AdmissionReceipt admission;
    private final long retiredAtNanos;

    CarrierRetirementReceipt(AdmissionReceipt admission) {
        this.admission = admission;
        this.retiredAtNanos = System.nanoTime();
    }

    public AdmissionReceipt getAdmission() {
        return admission;
    }

    public CarrierLeaseId getLeaseId() {
        return admission.getLease().getId();
    }

    public long getRetiredAtNanos() {
        return retiredAtNanos;
    }

    public boolean matches(AdmissionReceipt candidate) {
        return candidate != null && admission.getLease() == candidate.getLease();
    }
}
