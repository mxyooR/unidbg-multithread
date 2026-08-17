package com.github.unidbg.thread;

import com.github.unidbg.Emulator;
import com.github.unidbg.signal.SigSet;
import com.github.unidbg.signal.SignalTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

abstract class AbstractTask extends BaseTask implements Task {

    protected final int id;

    private volatile TaskThreadBinding threadBinding;

    public AbstractTask(int id) {
        this.id = id;
    }

    private SigSet sigMaskSet;
    private SigSet sigPendingSet;

    @Override
    public SigSet getSigMaskSet() {
        return sigMaskSet;
    }

    @Override
    public SigSet getSigPendingSet() {
        return sigPendingSet;
    }

    @Override
    public void setSigMaskSet(SigSet sigMaskSet) {
        this.sigMaskSet = sigMaskSet;
    }

    @Override
    public void setSigPendingSet(SigSet sigPendingSet) {
        this.sigPendingSet = sigPendingSet;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public final TaskThreadBinding getThreadBinding() {
        return threadBinding;
    }

    final void attachThreadBinding(TaskThreadBinding binding) {
        if (binding == null) {
            throw new NullPointerException("binding");
        }
        TaskThreadBinding existing = threadBinding;
        if (existing != null && existing.isActive() && existing != binding) {
            throw new IllegalStateException("task already has an active guest-thread binding");
        }
        threadBinding = binding;
    }

    final void detachThreadBinding() {
        threadBinding = null;
    }

    private final List<SignalTask> signalTaskList = new ArrayList<>();

    @Override
    public final void addSignalTask(SignalTask task) {
        signalTaskList.add(task);

        Waiter waiter = getWaiter();
        if (waiter != null) {
            waiter.onSignal(task);
        }
    }

    @Override
    public void removeSignalTask(SignalTask task) {
        signalTaskList.remove(task);
    }

    @Override
    public List<SignalTask> getSignalTaskList() {
        return signalTaskList.isEmpty() ? Collections.<SignalTask>emptyList() : new ArrayList<>(signalTaskList);
    }

    @Override
    public boolean setErrno(Emulator<?> emulator, int errno) {
        TaskThreadBinding binding = threadBinding;
        if (binding != null && binding.isActive()) {
            binding.getGuestThread().getExecutionState().setErrno(errno);
        }
        return false;
    }

    @Override
    protected final String getStatus() {
        if (isFinish()) {
            return "Finished";
        } else if (canDispatch()) {
            return "Runnable";
        } else {
            return "Paused";
        }
    }

}
