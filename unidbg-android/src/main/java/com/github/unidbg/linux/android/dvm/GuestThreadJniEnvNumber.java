package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.pointer.UnidbgPointer;
import com.sun.jna.Pointer;

/** Resolves JNIEnv only after the invocation carrier owns a guest thread. */
final class GuestThreadJniEnvNumber extends Number {

    private static final long serialVersionUID = 1L;

    private final BaseVM vm;
    private final Pointer fallback;

    GuestThreadJniEnvNumber(BaseVM vm, Pointer fallback) {
        this.vm = vm;
        this.fallback = fallback;
    }

    @Override
    public int intValue() {
        return (int) longValue();
    }

    @Override
    public long longValue() {
        return UnidbgPointer.nativeValue(vm.attachCurrentThreadJni(fallback));
    }

    @Override
    public float floatValue() {
        return longValue();
    }

    @Override
    public double doubleValue() {
        return longValue();
    }
}
