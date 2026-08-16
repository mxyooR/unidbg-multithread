package com.github.unidbg.thread;

/**
 * Exact result for one invocation. Non-terminal backend traffic is represented
 * separately from a command terminal so a handoff cannot look like a return.
 */
public final class InvocationResult {

    public enum State {
        COMPLETED,
        FAULT,
        TIMEOUT,
        CANCELLED,
        NON_TERMINAL
    }

    public enum Kind {
        VALUE,
        NULL_VALUE,
        FAULT,
        TIMEOUT,
        CANCELLED,
        TIMESLICE,
        BACKEND_STOP,
        FUTEX_WAIT
    }

    public static final class Ownership {
        private final long invocationId;
        private final long generation;
        private final long submitterThreadId;
        private final String submitterThread;
        private final String operation;

        Ownership(long invocationId, long generation, long submitterThreadId,
                  String submitterThread, String operation) {
            this.invocationId = invocationId;
            this.generation = generation;
            this.submitterThreadId = submitterThreadId;
            this.submitterThread = submitterThread;
            this.operation = operation;
        }

        public long getInvocationId() {
            return invocationId;
        }

        public long getGeneration() {
            return generation;
        }

        public long getSubmitterThreadId() {
            return submitterThreadId;
        }

        public String getSubmitterThread() {
            return submitterThread;
        }

        public String getOperation() {
            return operation;
        }
    }

    private final State state;
    private final Kind kind;
    private final Number value;
    private final String detail;
    private final Throwable fault;
    private final Ownership ownership;

    private InvocationResult(State state, Kind kind, Number value,
                             String detail, Throwable fault, Ownership ownership) {
        this.state = state;
        this.kind = kind;
        this.value = value;
        this.detail = detail;
        this.fault = fault;
        this.ownership = ownership;
    }

    public static InvocationResult completed(Number value) {
        return value == null
                ? new InvocationResult(State.COMPLETED, Kind.NULL_VALUE, null, null, null, null)
                : new InvocationResult(State.COMPLETED, Kind.VALUE, value, null, null, null);
    }

    public static InvocationResult fault(String detail, Throwable fault) {
        return new InvocationResult(State.FAULT, Kind.FAULT, null, detail, fault, null);
    }

    public static InvocationResult timeout(String detail) {
        return new InvocationResult(State.TIMEOUT, Kind.TIMEOUT, null, detail, null, null);
    }

    public static InvocationResult cancelled(String detail) {
        return new InvocationResult(State.CANCELLED, Kind.CANCELLED, null, detail, null, null);
    }

    public static InvocationResult timeslice() {
        return new InvocationResult(State.NON_TERMINAL, Kind.TIMESLICE, null, null, null, null);
    }

    public static InvocationResult backendStop() {
        return new InvocationResult(State.NON_TERMINAL, Kind.BACKEND_STOP, null, null, null, null);
    }

    public static InvocationResult futexWait() {
        return new InvocationResult(State.NON_TERMINAL, Kind.FUTEX_WAIT, null, null, null, null);
    }

    InvocationResult withOwnership(Ownership expected) {
        if (ownership != null && !sameOwnership(ownership, expected)) {
            throw new IllegalArgumentException("invocation result belongs to another owner");
        }
        return new InvocationResult(state, kind, value, detail, fault, expected);
    }

    private static boolean sameOwnership(Ownership left, Ownership right) {
        return left.invocationId == right.invocationId
                && left.generation == right.generation
                && left.submitterThreadId == right.submitterThreadId;
    }

    public State getState() {
        return state;
    }

    public Kind getKind() {
        return kind;
    }

    public Number getValue() {
        return value;
    }

    public String getDetail() {
        return detail;
    }

    public Throwable getFault() {
        return fault;
    }

    public Ownership getOwnership() {
        return ownership;
    }

    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.FAULT
                || state == State.TIMEOUT || state == State.CANCELLED;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isFault() {
        return state == State.FAULT;
    }

    public boolean isCancelled() {
        return state == State.CANCELLED;
    }

    public boolean isNonTerminal() {
        return state == State.NON_TERMINAL;
    }
}
