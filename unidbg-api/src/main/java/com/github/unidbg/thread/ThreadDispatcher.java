package com.github.unidbg.thread;

import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.SignalTask;

import java.util.List;
import java.util.concurrent.TimeUnit;

public interface ThreadDispatcher extends SignalOps {

    void addThread(ThreadTask task);

    List<Task> getTaskList();

    Number runMainForResult(MainTask main);

    /** Runs a non-main carrier through the dispatcher-owned backend. */
    Number runThreadForResult(ThreadTask task);

    /**
     * Submits a carrier with explicit invocation ownership. The returned record
     * is the only source of identity, terminal and cleanup for this call.
     */
    default InvocationRecord submitInvocation(ThreadTask task,
                                               InvocationContext context,
                                               InvocationReferenceScope referenceScope) {
        throw new UnsupportedOperationException("invocation ownership is not supported");
    }

    /** Runs a carrier and returns its exact invocation-owned outcome. */
    default InvocationOutcome runThreadForOutcome(ThreadTask task,
                                                   InvocationContext context) {
        throw new UnsupportedOperationException("invocation outcomes are not supported");
    }

    /** Runs a carrier with a caller-prepared invocation reference scope. */
    default InvocationOutcome runThreadForOutcome(ThreadTask task,
                                                   InvocationContext context,
                                                   InvocationReferenceScope referenceScope) {
        if (referenceScope != null) {
            throw new UnsupportedOperationException("invocation reference scopes are not supported");
        }
        return runThreadForOutcome(task, context);
    }

    /** Requests cancellation; completion is published after carrier retirement. */
    default boolean cancelInvocation(InvocationRecord invocation, String detail) {
        return false;
    }

    /** Requests a timeout terminal; completion waits for carrier retirement. */
    default boolean timeoutInvocation(InvocationRecord invocation, String detail) {
        return false;
    }

    /** Returns the invocation driven by the current backend-owner thread, if any. */
    default InvocationRecord getRunningInvocation() {
        return null;
    }

    /** Runtime identity of the current backend carrier, if any. */
    default GuestThreadIncarnation getRunningGuestThread() {
        return null;
    }

    /** Current run lifecycle owner. */
    default RunContext getRunContext() {
        return null;
    }

    /** Admission proof for the current backend carrier, if any. */
    default AdmissionReceipt getRunningAdmission() {
        return null;
    }

    /** True when a different host thread currently owns the backend loop. */
    default boolean isBackendOwnedByAnotherThread() {
        return false;
    }

    void runThreads(long timeout, TimeUnit unit);

    int getTaskCount();

    boolean sendSignal(int tid, int sig, SignalTask signalTask);

    RunnableTask getRunningTask();

    /** Releases run-owned identity state before the emulator backend is destroyed. */
    default void dispose() {
    }

}
