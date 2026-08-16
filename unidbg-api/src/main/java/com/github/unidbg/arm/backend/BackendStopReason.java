package com.github.unidbg.arm.backend;

/**
 * Reason reported by a backend when an emulation run returns.
 *
 * <p>The values are deliberately backend-neutral.  A scheduler can therefore
 * distinguish a normal function return from a cooperative handoff without
 * parsing backend-specific exceptions or log messages.</p>
 */
public enum BackendStopReason {
    NONE(0),
    NORMAL(1),
    TIMESLICE(2),
    EMU_STOP(3),
    FAULT(4),
    TIMEOUT(5),
    UNTIL(-1),
    SYSCALL(-1),
    HOOK(-1);

    private final int code;

    BackendStopReason(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static BackendStopReason fromCode(int code) {
        for (BackendStopReason reason : values()) {
            if (reason.code == code && code >= 0) {
                return reason;
            }
        }
        return FAULT;
    }
}
