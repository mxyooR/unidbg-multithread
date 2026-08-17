package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.pointer.UnidbgPointer;

/** Stable JNI attachment state owned by one guest thread and one VM. */
final class GuestThreadJniState {

    private final UnidbgPointer environment;
    private boolean attached;

    GuestThreadJniState(UnidbgPointer environment) {
        this.environment = environment;
    }

    synchronized UnidbgPointer attach() {
        attached = true;
        return environment;
    }

    synchronized void detach() {
        attached = false;
    }

    synchronized boolean isAttached() {
        return attached;
    }

    UnidbgPointer getEnvironment() {
        return environment;
    }
}
