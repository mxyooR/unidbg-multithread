package com.github.unidbg.thread;

import com.github.unidbg.Emulator;
import com.github.unidbg.LongJumpException;
import com.github.unidbg.arm.backend.Backend;
import unicorn.Arm64Const;
import unicorn.ArmConst;

public class ThreadContextSwitchException extends LongJumpException {

    /** Why the current guest carrier yielded the single backend. */
    public enum Reason {
        UNKNOWN,
        TIMESLICE,
        SAFEPOINT,
        FUTEX_WAIT,
        FUTEX_WAKE,
        SYSCALL,
        BACKEND_STOP,
        FAULT,
        THREAD_EXIT
    }

    private Reason reason = Reason.UNKNOWN;

    public ThreadContextSwitchException setReason(Reason reason) {
        this.reason = reason == null ? Reason.UNKNOWN : reason;
        return this;
    }

    public Reason getReason() {
        return reason;
    }

    /** Compatibility helper for existing futex/scheduler call sites. */
    public ThreadContextSwitchException setTimeslice() {
        return setReason(Reason.TIMESLICE);
    }

    public boolean isTimeslice() {
        return reason == Reason.TIMESLICE;
    }

    private boolean setReturnValue;
    private long returnValue;

    public ThreadContextSwitchException setReturnValue(long returnValue) {
        this.setReturnValue = true;
        this.returnValue = returnValue;
        return this;
    }

    private boolean setErrno;
    private int errno;

    public ThreadContextSwitchException setErrno(int errno) {
        this.setErrno = true;
        this.errno = errno;
        return this;
    }

    public void syncReturnValue(Emulator<?> emulator) {
        if (setReturnValue) {
            Backend backend = emulator.getBackend();
            backend.reg_write(emulator.is32Bit() ? ArmConst.UC_ARM_REG_R0 : Arm64Const.UC_ARM64_REG_X0, returnValue);
        }
        if (setErrno) {
            emulator.getMemory().setErrno(errno);
        }
    }

}
