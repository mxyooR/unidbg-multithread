package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.thread.InvocationReferenceScope;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** VM-owned local references for one invocation carrier. */
final class InvocationLocalReferenceScope implements InvocationReferenceScope {

    private final BaseVM vm;
    private final Deque<Map<Integer, BaseVM.ObjRef>> frames = new ArrayDeque<>();
    private DvmObject<?> pendingException;
    private boolean bound;
    private boolean carrierRetired;
    private boolean outcomeAcknowledged;
    private boolean closed;

    InvocationLocalReferenceScope(BaseVM vm) {
        this.vm = vm;
        frames.push(new HashMap<Integer, BaseVM.ObjRef>());
    }

    synchronized int addLocalObject(DvmObject<?> object) {
        ensureOpen();
        return vm.addObjectToInvocationScope(object, frames.peek());
    }

    synchronized BaseVM.ObjRef getLocalReference(int hash) {
        if (closed) {
            return null;
        }
        for (Map<Integer, BaseVM.ObjRef> frame : frames) {
            BaseVM.ObjRef ref = frame.get(hash);
            if (ref != null) {
                return ref;
            }
        }
        return null;
    }

    synchronized void pushLocalFrame() {
        ensureOpen();
        frames.push(new HashMap<Integer, BaseVM.ObjRef>());
    }

    synchronized long popLocalFrame(long resultHash) {
        ensureOpen();
        if (frames.size() <= 1) {
            throw new IllegalStateException("cannot pop the invocation root local frame");
        }
        Map<Integer, BaseVM.ObjRef> top = frames.peek();
        int hash = (int) resultHash;
        BaseVM.ObjRef promoted = hash == 0 ? null : top.remove(hash);
        if (hash != 0 && promoted == null && getLocalReference(hash) == null) {
            DvmObject<?> global = vm.getGlobalOrWeakObject(hash);
            if (global == null) {
                throw new IllegalStateException("PopLocalFrame result is not owned by this invocation");
            }
            promoted = new BaseVM.ObjRef(global, false);
        }
        frames.pop();
        vm.deleteInvocationLocalRefs(top);
        if (promoted != null) {
            frames.peek().put(hash, promoted);
        }
        return resultHash;
    }

    synchronized void deleteLocalRef(int hash) {
        ensureOpen();
        if (hash == 0) {
            return;
        }
        for (Map<Integer, BaseVM.ObjRef> frame : frames) {
            BaseVM.ObjRef removed = frame.remove(hash);
            if (removed != null) {
                removed.obj.onDeleteRef();
                return;
            }
        }
    }

    synchronized void setPendingException(DvmObject<?> exception) {
        ensureOpen();
        pendingException = exception;
    }

    synchronized DvmObject<?> getPendingException() {
        return closed ? null : pendingException;
    }

    synchronized void clearPendingException() {
        pendingException = null;
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
        DvmObject<?> releasedException = null;
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
            released = new HashMap<>();
            for (Map<Integer, BaseVM.ObjRef> frame : frames) {
                released.putAll(frame);
                frame.clear();
            }
            frames.clear();
            releasedException = pendingException;
            pendingException = null;
        }
        vm.deleteInvocationLocalRefs(released);
        if (releasedException != null) {
            releasedException.onDeleteRef();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("invocation reference scope is closed");
        }
    }
}
