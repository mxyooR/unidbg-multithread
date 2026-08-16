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

    /** Returns the invocation currently admitted to the backend, if any. */
    default InvocationRecord getRunningInvocation() {
        return null;
    }

    void runThreads(long timeout, TimeUnit unit);

    int getTaskCount();

    boolean sendSignal(int tid, int sig, SignalTask signalTask);

    RunnableTask getRunningTask();

}
