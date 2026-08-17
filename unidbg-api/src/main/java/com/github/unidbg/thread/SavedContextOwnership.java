package com.github.unidbg.thread;

/**
 * Immutable ownership proof captured with a backend context. A context belongs
 * to one task, one binding epoch and, when applicable, one invocation
 * generation; it must not be restored merely because its native handle still
 * exists.
 */
public final class SavedContextOwnership {

    private final Task task;
    private final TaskThreadBinding binding;
    private final long bindingId;
    private final long bindingEpoch;
    private final GuestThreadIncarnation guestThread;
    private final long guestThreadIncarnationId;
    private final long runId;
    private final long invocationId;
    private final long invocationGeneration;
    private final TaskStackEvidence stackEvidence;
    private final Object stackAllocationIdentity;
    private final long stackAllocationSequence;
    private final long stackCanary;
    private final boolean tpidrEl0Captured;
    private final long tpidrEl0;

    static SavedContextOwnership capture(Task task, TaskThreadBinding binding,
                                         AdmissionReceipt admission,
                                         TaskStackEvidence stackEvidence,
                                         boolean tpidrEl0Captured,
                                         long tpidrEl0) {
        if (task == null || binding == null || binding.getTask() != task
                || task.getThreadBinding() != binding || !binding.isActive()) {
            throw failure("backend context has no exact live task binding");
        }
        if (binding.getGuestThread().getActiveBinding() != binding) {
            throw failure("backend context binding is not authoritative");
        }
        if (admission != null && (admission.getBinding() != binding
                || admission.getLease().isRetired())) {
            throw failure("backend context admission does not match its binding");
        }
        return new SavedContextOwnership(task, binding, admission, stackEvidence,
                tpidrEl0Captured, tpidrEl0);
    }

    private SavedContextOwnership(Task task, TaskThreadBinding binding,
                                  AdmissionReceipt admission,
                                  TaskStackEvidence stackEvidence,
                                  boolean tpidrEl0Captured, long tpidrEl0) {
        this.task = task;
        this.binding = binding;
        this.bindingId = binding.getBindingId();
        this.bindingEpoch = binding.getEpoch();
        this.guestThread = binding.getGuestThread();
        this.guestThreadIncarnationId = guestThread.getIncarnationId();
        this.runId = binding.getRunContext().getRunId();
        this.invocationId = admission == null ? 0L : admission.getInvocationId();
        this.invocationGeneration = admission == null ? 0L : admission.getGeneration();
        this.stackEvidence = stackEvidence;
        this.stackAllocationIdentity = stackEvidence == null
                ? null : stackEvidence.getBackendAllocationIdentity();
        this.stackAllocationSequence = stackEvidence == null
                ? 0L : stackEvidence.getAllocationSequence();
        this.stackCanary = stackEvidence == null
                ? 0L : stackEvidence.getCanaryReadBack();
        this.tpidrEl0Captured = tpidrEl0Captured;
        this.tpidrEl0 = tpidrEl0;
    }

    void requireCurrent(Task currentTask, TaskThreadBinding currentBinding,
                        AdmissionReceipt currentAdmission,
                        TaskStackEvidence currentStackEvidence) {
        if (currentTask != task) {
            throw failure("saved backend context belongs to another task");
        }
        if (currentBinding != binding || task.getThreadBinding() != binding
                || binding.getTask() != task || !binding.isActive()) {
            throw failure("saved backend context binding is stale");
        }
        if (binding.getBindingId() != bindingId || binding.getEpoch() != bindingEpoch
                || binding.getGuestThread() != guestThread
                || guestThread.getIncarnationId() != guestThreadIncarnationId
                || binding.getRunContext().getRunId() != runId
                || guestThread.isRetired()
                || guestThread.getActiveBinding() != binding) {
            throw failure("saved backend context runtime identity changed");
        }
        requireCurrentAdmission(currentAdmission);
        requireCurrentStack(currentStackEvidence);
    }

    private void requireCurrentAdmission(AdmissionReceipt currentAdmission) {
        if (invocationId == 0L) {
            if (currentAdmission != null) {
                throw failure("non-invocation context entered an invocation carrier");
            }
            return;
        }
        if (currentAdmission == null
                || currentAdmission.getInvocationId() != invocationId
                || currentAdmission.getGeneration() != invocationGeneration
                || currentAdmission.getBinding() != binding
                || currentAdmission.getLease().isRetired()) {
            throw failure("saved backend context invocation generation is stale");
        }
    }

    private void requireCurrentStack(TaskStackEvidence currentStackEvidence) {
        if (stackEvidence == null) {
            if (currentStackEvidence != null) {
                throw failure("saved backend context acquired a different stack");
            }
            return;
        }
        if (currentStackEvidence == null
                || currentStackEvidence.getBackendAllocationIdentity()
                != stackAllocationIdentity
                || currentStackEvidence.getAllocationSequence()
                != stackAllocationSequence) {
            throw failure("saved backend context stack allocation changed");
        }
        long observedCanary = currentStackEvidence.readCanaryFromBackend();
        if (observedCanary != stackCanary) {
            throw failure("saved backend context stack canary changed");
        }
        currentStackEvidence.requireCanaryIntact();
    }

    void requireRestoredTpidrEl0(long observedTpidrEl0) {
        if (tpidrEl0Captured && observedTpidrEl0 != tpidrEl0) {
            throw failure("restored backend context TPIDR_EL0 changed");
        }
    }

    public long getBindingId() {
        return bindingId;
    }

    public long getBindingEpoch() {
        return bindingEpoch;
    }

    public long getGuestThreadIncarnationId() {
        return guestThreadIncarnationId;
    }

    public long getRunId() {
        return runId;
    }

    public long getInvocationId() {
        return invocationId;
    }

    public long getInvocationGeneration() {
        return invocationGeneration;
    }

    public boolean isTpidrEl0Captured() {
        return tpidrEl0Captured;
    }

    public long getTpidrEl0() {
        return tpidrEl0;
    }

    private static SavedContextOwnershipException failure(String message) {
        return new SavedContextOwnershipException(message);
    }
}
