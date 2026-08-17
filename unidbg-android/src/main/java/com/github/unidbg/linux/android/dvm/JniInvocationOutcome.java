package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.thread.InvocationOutcome;

/** A resolved JNI value together with the exact invocation terminal. */
public final class JniInvocationOutcome<T> {

    private final T value;
    private final InvocationOutcome invocationOutcome;

    JniInvocationOutcome(T value, InvocationOutcome invocationOutcome) {
        if (invocationOutcome == null) {
            throw new NullPointerException("invocationOutcome");
        }
        this.value = value;
        this.invocationOutcome = invocationOutcome;
    }

    public T getValue() {
        return value;
    }

    public InvocationOutcome getInvocationOutcome() {
        return invocationOutcome;
    }
}
