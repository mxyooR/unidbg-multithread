package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.thread.InvocationReferenceScope;

import java.util.HashMap;
import java.util.Map;

/** VM-owned local references for one invocation carrier. */
final class InvocationLocalReferenceScope implements InvocationReferenceScope {

    private final BaseVM vm;
    private final Map<Integer, BaseVM.ObjRef> references = new HashMap<>();
    private boolean bound;
    private boolean carrierRetired;
    private boolean outcomeAcknowledged;
    private boolean closed;

    InvocationLocalReferenceScope(BaseVM vm) {
        this.vm = vm;
    }

    synchronized int addLocalObject(DvmObject<?> object) {
        ensureOpen();
        return vm.addObjectToInvocationScope(object, references);
    }

    synchronized BaseVM.ObjRef getLocalReference(int hash) {
        return closed ? null : references.get(hash);
    }

    boolean belongsTo(BaseVM candidate) {
        return vm == candidate;
    }

    @Override
    public synchronized void bindToCarrier() {
        ensureOpen();
        if (bound) {
            throw new IllegalStateException("invocation reference scope is already bound");
        }
        bound = true;
    }

    @Override
    public void markCarrierRetired() {
        releaseWhenReady(true, false, false);
    }

    @Override
    public void acknowledgeOutcome() {
        releaseWhenReady(false, true, false);
    }

    @Override
    public void discardUnbound() {
        releaseWhenReady(false, false, true);
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    private void releaseWhenReady(boolean retired, boolean acknowledged, boolean unbound) {
        Map<Integer, BaseVM.ObjRef> released = null;
        synchronized (this) {
            if (closed) {
                return;
            }
            carrierRetired |= retired;
            outcomeAcknowledged |= acknowledged;
            if (unbound && bound) {
                return;
            }
            if (!unbound && (!carrierRetired || !outcomeAcknowledged)) {
                return;
            }
            closed = true;
            released = new HashMap<>(references);
            references.clear();
        }
        vm.deleteInvocationLocalRefs(released);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("invocation reference scope is closed");
        }
    }
}
