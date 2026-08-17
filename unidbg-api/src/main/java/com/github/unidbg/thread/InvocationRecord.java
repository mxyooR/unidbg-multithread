package com.github.unidbg.thread;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ownership record for one native invocation. The record, rather than a task
 * list or a global result slot, owns identity, context, terminal and cleanup.
 */
public final class InvocationRecord {

    public enum State {
        RESERVED,
        QUEUED,
        ADMITTED,
        SUSPENDED,
        CANCEL_REQUESTED,
        QUIESCING,
        TERMINAL,
        CANCELLED
    }

    private final long invocationId;
    private final long generation;
    private final Thread submitterThread;
    private final InvocationContext context;
    private final ThreadTask carrier;
    private final CompletableFuture<InvocationOutcome> completion = new CompletableFuture<>();
    private final InvocationResult.Ownership ownership;
    private volatile InvocationReferenceScope referenceScope;
    private volatile State state = State.RESERVED;
    private volatile InvocationResult terminal;
    private volatile InvocationResult requestedTerminal;
    private volatile boolean carrierRetired;
    private volatile boolean outcomeAcknowledged;

    InvocationRecord(long invocationId, long generation, Thread submitterThread,
                     ThreadTask carrier, InvocationContext context,
                     InvocationReferenceScope referenceScope) {
        if (invocationId <= 0L || generation <= 0L || submitterThread == null
                || carrier == null || context == null) {
            throw new IllegalArgumentException("invalid invocation identity");
        }
        this.invocationId = invocationId;
        this.generation = generation;
        this.submitterThread = submitterThread;
        this.carrier = carrier;
        this.context = context;
        this.ownership = new InvocationResult.Ownership(
                invocationId, generation, submitterThread.getId(),
                submitterThread.getName(), context.getOperation());
        if (referenceScope != null) {
            installReferenceScope(referenceScope);
        }
    }

    public long getInvocationId() {
        return invocationId;
    }

    public long getGeneration() {
        return generation;
    }

    public Thread getSubmitterThread() {
        return submitterThread;
    }

    public ThreadTask getCarrier() {
        return carrier;
    }

    public InvocationContext getContext() {
        return context;
    }

    public InvocationReferenceScope getReferenceScope() {
        return referenceScope;
    }

    public synchronized boolean installReferenceScope(InvocationReferenceScope scope) {
        if (scope == null || referenceScope != null) {
            return false;
        }
        scope.bindToCarrier();
        referenceScope = scope;
        if (carrierRetired) {
            scope.markCarrierRetired();
        }
        if (outcomeAcknowledged) {
            scope.acknowledgeOutcome();
        }
        return true;
    }

    public State getState() {
        return state;
    }

    public InvocationResult getTerminal() {
        return terminal;
    }

    synchronized void markQueued() {
        requireState(State.RESERVED, State.SUSPENDED);
        state = State.QUEUED;
    }

    synchronized boolean admit() {
        if (state != State.QUEUED && state != State.SUSPENDED) {
            return false;
        }
        state = State.ADMITTED;
        return true;
    }

    synchronized boolean suspend() {
        if (state != State.ADMITTED || terminal != null) {
            return false;
        }
        state = State.SUSPENDED;
        return true;
    }

    public boolean requestCancellation(String detail) {
        return requestTermination(InvocationResult.cancelled(detail));
    }

    public boolean requestTimeout(String detail) {
        return requestTermination(InvocationResult.timeout(detail));
    }

    private synchronized boolean requestTermination(InvocationResult requested) {
        if (terminal != null || state == State.CANCELLED || state == State.TERMINAL) {
            return false;
        }
        if (state == State.CANCEL_REQUESTED || state == State.QUIESCING) {
            return false;
        }
        requestedTerminal = requested;
        state = State.CANCEL_REQUESTED;
        return true;
    }

    synchronized boolean beginQuiescing() {
        if (state != State.CANCEL_REQUESTED) {
            return false;
        }
        state = State.QUIESCING;
        return true;
    }

    synchronized boolean complete(InvocationResult result) {
        if (result == null || !result.isTerminal() || terminal != null
                || state == State.CANCELLED) {
            return false;
        }
        if (result.isCompleted() && isTerminationRequested()) {
            return false;
        }
        terminal = result.withOwnership(ownership);
        state = State.TERMINAL;
        completion.complete(new InvocationOutcome(this, terminal));
        return true;
    }

    synchronized boolean finishRequestedTermination() {
        if (terminal != null || state == State.TERMINAL || state == State.CANCELLED) {
            return false;
        }
        if (state != State.QUIESCING) {
            return false;
        }
        InvocationResult requested = requestedTerminal == null
                ? InvocationResult.cancelled("invocation cancelled") : requestedTerminal;
        terminal = requested.withOwnership(ownership);
        state = requested.isCancelled() ? State.CANCELLED : State.TERMINAL;
        completion.complete(new InvocationOutcome(this, terminal));
        return true;
    }

    synchronized void markCarrierRetired() {
        if (carrierRetired) {
            return;
        }
        carrierRetired = true;
        if (referenceScope != null) {
            referenceScope.markCarrierRetired();
        }
    }

    synchronized void acknowledgeOutcome() {
        if (outcomeAcknowledged) {
            return;
        }
        outcomeAcknowledged = true;
        if (referenceScope != null) {
            referenceScope.acknowledgeOutcome();
        }
    }

    public boolean isCarrierRetired() {
        return carrierRetired;
    }

    public boolean isOutcomeAcknowledged() {
        return outcomeAcknowledged;
    }

    public InvocationOutcome await() throws InterruptedException, ExecutionException {
        return completion.get();
    }

    public InvocationOutcome await(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return completion.get(timeout, unit);
    }

    public boolean isDone() {
        return completion.isDone();
    }

    boolean isTerminationRequested() {
        State current = state;
        return current == State.CANCEL_REQUESTED || current == State.QUIESCING;
    }

    private void requireState(State... allowed) {
        for (State candidate : allowed) {
            if (state == candidate) {
                return;
            }
        }
        throw new IllegalStateException("invalid invocation state: " + state);
    }
}
