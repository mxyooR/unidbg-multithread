package com.github.unidbg.thread;

/** Immutable value plus terminal evidence for one invocation. */
public final class InvocationOutcome implements AutoCloseable {

    private final InvocationRecord invocation;
    private final InvocationResult result;
    private boolean acknowledged;

    InvocationOutcome(InvocationRecord invocation, InvocationResult result) {
        if (invocation == null || result == null || !result.isTerminal()
                || result.getOwnership() == null
                || result.getOwnership().getInvocationId() != invocation.getInvocationId()
                || result.getOwnership().getGeneration() != invocation.getGeneration()
                || result.getOwnership().getSubmitterThreadId()
                != invocation.getSubmitterThread().getId()) {
            throw new IllegalArgumentException("result does not belong to invocation");
        }
        this.invocation = invocation;
        this.result = result;
    }

    public InvocationRecord getInvocation() {
        return invocation;
    }

    public InvocationResult getResult() {
        return result;
    }

    public Number getValue() {
        return result.getValue();
    }

    public synchronized void acknowledgeOutcome() {
        if (acknowledged) {
            return;
        }
        acknowledged = true;
        invocation.acknowledgeOutcome();
    }

    public synchronized boolean isAcknowledged() {
        return acknowledged;
    }

    @Override
    public void close() {
        acknowledgeOutcome();
    }
}
