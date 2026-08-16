package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.pointer.UnidbgPointer;
import unicorn.ArmConst;

import java.util.Arrays;

/** Generic AArch32 carrier for a foreign host-thread native call. */
public final class NativeWorkerTask32 extends ThreadTask {

    private final long address;
    private final boolean paddingArgument;
    private final Number[] arguments;

    public NativeWorkerTask32(int pid, long address, long until,
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
        backend.reg_write(ArmConst.UC_ARM_REG_LR, until);
        return emulator.emulate(address, until);
    }

    @Override
    public String toThreadString() {
        return "NativeWorkerTask32 address=0x" + Long.toHexString(address)
                + ", arguments=" + Arrays.toString(arguments);
    }
}
