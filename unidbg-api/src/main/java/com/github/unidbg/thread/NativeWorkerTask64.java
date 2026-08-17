package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.pointer.UnidbgPointer;
import unicorn.Arm64Const;

import java.util.Arrays;

/**
 * Generic AArch64 carrier for a native call submitted by a foreign host
 * thread. The dispatcher still owns the single backend; this task only gives
 * the call an independent guest stack and non-main task identity.
 */
public final class NativeWorkerTask64 extends ThreadTask {

    private final long address;
    private final boolean paddingArgument;
    private final Number[] arguments;

    public NativeWorkerTask64(int pid, long address, long until,
                              boolean paddingArgument, Number... arguments) {
        super(pid, until);
        this.address = address;
        this.paddingArgument = paddingArgument;
        this.arguments = arguments == null ? new Number[0] : arguments.clone();
    }

    @Override
    protected Number runThread(AbstractEmulator<?> emulator) {
        Backend backend = emulator.getBackend();
        UnidbgPointer stack = allocateStack(emulator);
        ARM.initArgs(emulator, paddingArgument, stack.peer, arguments);
        captureStackEvidence(emulator);
        backend.reg_write(Arm64Const.UC_ARM64_REG_LR, until);
        return emulator.emulate(address, until);
    }

    @Override
    public String toThreadString() {
        return "NativeWorkerTask64 address=0x" + Long.toHexString(address)
                + ", arguments=" + Arrays.toString(arguments);
    }
}
