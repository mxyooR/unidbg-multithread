package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.signal.SigSet;
import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.signal.UnixSigSet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * 抢占式调度
 */
public class UniThreadDispatcher implements ThreadDispatcher {

    private static final Log log = LogFactory.getLog(UniThreadDispatcher.class);

    private final List<Task> taskList = new ArrayList<>();
    private final AbstractEmulator<?> emulator;
    private final RunContext runContext;
    private final ConcurrentLinkedDeque<Task> externalTaskQueue = new ConcurrentLinkedDeque<>();
    private final ArrayDeque<InvocationRecord> invocationQueue = new ArrayDeque<>();
    private final Map<Task, InvocationRecord> invocationByTask = new IdentityHashMap<>();
    private final Map<Task, TaskThreadBinding> threadBindingByTask = new IdentityHashMap<>();
    private long nextInvocationId;
    private long nextGeneration;
    private volatile boolean dispatching;
    private boolean dispatchExiting;
    private volatile Thread dispatchOwner;
    private volatile InvocationRecord runningInvocation;
    private volatile TaskThreadBinding runningThreadBinding;
    private volatile AdmissionReceipt runningAdmission;

    public UniThreadDispatcher(AbstractEmulator<?> emulator) {
        this.emulator = emulator;
        this.runContext = new RunContext();
    }

    private final List<ThreadTask> threadTaskList = new ArrayList<>();

    @Override
    public void addThread(ThreadTask task) {
        synchronized (this) {
            ensureThreadBinding(task);
            threadTaskList.add(task);
            notifyAll();
        }
    }

    /** True while one host thread owns the backend dispatch loop. */
    public boolean isDispatching() {
        return dispatching;
    }

    /** True when the current caller is the backend owner. */
    public boolean isDispatchingOnCurrentThread() {
        return dispatching && dispatchOwner == Thread.currentThread();
    }

    /** True when another host thread currently owns the backend loop. */
    public boolean isDispatchingOnOtherThread() {
        return dispatching && dispatchOwner != Thread.currentThread();
    }

    @Override
    public boolean isBackendOwnedByAnotherThread() {
        return isDispatchingOnOtherThread();
    }

    /** Used by the emulator to decide whether a native instruction budget is useful. */
    public boolean hasMultipleRunnableSources() {
        return getTaskCount() > 1 || !externalTaskQueue.isEmpty();
    }

    public boolean hasExternalTasks() {
        return !externalTaskQueue.isEmpty();
    }

    @Override
    public InvocationRecord getRunningInvocation() {
        return dispatchOwner == Thread.currentThread() ? runningInvocation : null;
    }

    @Override
    public GuestThreadIncarnation getRunningGuestThread() {
        if (dispatchOwner != Thread.currentThread()) {
            return null;
        }
        TaskThreadBinding binding = runningThreadBinding;
        return binding == null ? null : binding.getGuestThread();
    }

    @Override
    public RunContext getRunContext() {
        return runContext;
    }

    @Override
    public AdmissionReceipt getRunningAdmission() {
        return dispatchOwner == Thread.currentThread() ? runningAdmission : null;
    }

    /** True when the current backend run has a queued handoff or cancellation. */
    public synchronized boolean shouldYieldCurrentTask() {
        InvocationRecord current = runningInvocation;
        if (current != null && current.isTerminationRequested()) {
            return true;
        }
        if (!externalTaskQueue.isEmpty()) {
            return true;
        }
        return !invocationQueue.isEmpty() && invocationQueue.peekFirst() != current;
    }

    /** Snapshot including pending and externally submitted tasks. */
    public synchronized List<Task> getAllTasksSnapshot() {
        List<Task> snapshot = new ArrayList<>(taskList);
        snapshot.addAll(threadTaskList);
        snapshot.addAll(externalTaskQueue);
        return snapshot;
    }

    /** Enqueues a carrier submitted by a host thread without touching backend state. */
    public void submitExternalTask(Task task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        externalTaskQueue.addLast(task);
        synchronized (this) {
            notifyAll();
        }
        requestBackendStop();
    }

    @Override
    public List<Task> getTaskList() {
        return taskList;
    }

    @Override
    public boolean sendSignal(int tid, int sig, SignalTask signalTask) {
        List<Task> list = new ArrayList<>();
        synchronized (this) {
            list.addAll(taskList);
            list.addAll(threadTaskList);
            list.addAll(externalTaskQueue);
        }
        boolean ret = false;
        for (Task task : list) {
            SignalOps signalOps = null;
            if (tid == 0 && task.isMainThread()) {
                signalOps = this;
            }
            if (tid == task.getId()) {
                signalOps = task;
            }
            if (signalOps == null) {
                continue;
            }
            SigSet sigSet = signalOps.getSigMaskSet();
            SigSet sigPendingSet = signalOps.getSigPendingSet();
            if (sigPendingSet == null) {
                sigPendingSet = new UnixSigSet(0);
                signalOps.setSigPendingSet(sigPendingSet);
            }
            if (sigSet != null && sigSet.containsSigNumber(sig)) {
                sigPendingSet.addSigNumber(sig);
                return false;
            }
            if (signalTask != null) {
                task.addSignalTask(signalTask);
                if (log.isTraceEnabled()) {
                    emulator.attach().debug();
                }
            } else {
                sigPendingSet.addSigNumber(sig);
            }
            ret = true;
            break;
        }
        return ret;
    }

    private RunnableTask runningTask;

    @Override
    public RunnableTask getRunningTask() {
        return runningTask;
    }

    /**
     * Runs a worker carrier to completion while preserving the single backend
     * ownership rule. Calls from a different host thread are submitted to the
     * owner loop and wait on a private result future; they never call Unicorn
     * directly.
     */
    @Override
    public Number runThreadForResult(ThreadTask task) {
        InvocationOutcome outcome = runThreadForOutcome(task,
                InvocationContext.builder().operation("thread-task").origin("ThreadDispatcher").build());
        try {
            InvocationResult result = outcome.getResult();
            if (!result.isCompleted()) {
                throw invocationFailure(result);
            }
            return outcome.getValue();
        } finally {
            outcome.acknowledgeOutcome();
        }
    }

    @Override
    public InvocationOutcome runThreadForOutcome(ThreadTask task, InvocationContext context) {
        return runThreadForOutcome(task, context, null);
    }

    @Override
    public InvocationOutcome runThreadForOutcome(ThreadTask task, InvocationContext context,
                                                  InvocationReferenceScope referenceScope) {
        InvocationRecord record = submitInvocation(task, context, referenceScope);
        return awaitInvocation(record);
    }

    private InvocationOutcome awaitInvocation(InvocationRecord record) {
        try {
            return record.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelInvocation(record, "host thread interrupted");
            record.acknowledgeOutcome();
            throw new IllegalStateException("interrupted while waiting for invocation", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("guest invocation failed", cause);
        }
    }

    private static IllegalStateException invocationFailure(InvocationResult result) {
        String detail = result.getDetail() == null
                ? "guest invocation ended with " + result.getState() : result.getDetail();
        return new IllegalStateException(detail, result.getFault());
    }

    @Override
    public InvocationRecord submitInvocation(ThreadTask task, InvocationContext context,
                                             InvocationReferenceScope referenceScope) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        InvocationContext actualContext = context == null
                ? InvocationContext.builder().build() : context;
        boolean queued;
        InvocationRecord record;
        synchronized (this) {
            awaitDispatchExit();
            if (dispatching && dispatchOwner == Thread.currentThread()) {
                throw new IllegalStateException(
                        "synchronous guest re-entry on the backend owner thread is not supported");
            }
            record = createInvocation(task, actualContext, referenceScope);
            queued = dispatching;
            if (queued) {
                externalTaskQueue.addLast(task);
                notifyAll();
            } else {
                dispatching = true;
                dispatchOwner = Thread.currentThread();
                taskList.add(0, task);
            }
        }
        if (queued) {
            requestBackendStop();
        } else {
            run(0, null);
        }
        return record;
    }

    @Override
    public boolean cancelInvocation(InvocationRecord invocation, String detail) {
        return requestInvocationTermination(invocation, detail, false);
    }

    @Override
    public boolean timeoutInvocation(InvocationRecord invocation, String detail) {
        return requestInvocationTermination(invocation, detail, true);
    }

    private boolean requestInvocationTermination(InvocationRecord invocation, String detail,
                                                 boolean timeout) {
        if (invocation == null) {
            return false;
        }
        synchronized (this) {
            if (invocationByTask.get(invocation.getCarrier()) != invocation
                    || !(timeout ? invocation.requestTimeout(detail)
                    : invocation.requestCancellation(detail))) {
                return false;
            }
            notifyAll();
        }
        requestBackendStop();
        return true;
    }

    /** Atomically chooses a main carrier or a foreign-thread invocation carrier. */
    public Number runFunctionForResult(MainTask main, ThreadTask foreign) {
        InvocationRecord foreignInvocation = null;
        synchronized (this) {
            awaitDispatchExit();
            if (dispatching && dispatchOwner != Thread.currentThread()) {
                foreignInvocation = createInvocation(foreign,
                        InvocationContext.builder()
                                .operation("thread-task")
                                .origin("ThreadDispatcher")
                                .build(), null);
                externalTaskQueue.addLast(foreign);
                notifyAll();
            } else if (!dispatching) {
                dispatching = true;
                dispatchOwner = Thread.currentThread();
            }
        }
        if (foreignInvocation == null) {
            return runMainForResult(main);
        }
        requestBackendStop();
        InvocationOutcome outcome = awaitInvocation(foreignInvocation);
        try {
            InvocationResult result = outcome.getResult();
            if (!result.isCompleted()) {
                throw invocationFailure(result);
            }
            return outcome.getValue();
        } finally {
            outcome.acknowledgeOutcome();
        }
    }

    @Override
    public Number runMainForResult(MainTask main) {
        synchronized (this) {
            awaitDispatchExit();
            if (dispatching && dispatchOwner != Thread.currentThread()) {
                throw new IllegalStateException("backend is already owned by another host thread");
            }
            if (!dispatching) {
                dispatching = true;
                dispatchOwner = Thread.currentThread();
            }
            ensureThreadBinding(main);
            taskList.add(0, main);
        }

        if (log.isDebugEnabled()) {
            log.debug("runMainForResult main=" + main);
        }

        Number ret = run(0, null);
        for (Iterator<Task> iterator = taskList.iterator(); iterator.hasNext(); ) {
            Task task = iterator.next();
            if (task.isFinish()) {
                if (log.isDebugEnabled()) {
                    log.debug("Finish task=" + task);
                }
                task.destroy(emulator);
                synchronized (this) {
                    iterator.remove();
                }
                for (SignalTask signalTask : task.getSignalTaskList()) {
                    signalTask.destroy(emulator);
                    task.removeSignalTask(signalTask);
                }
            }
        }
        return ret;
    }

    @Override
    public void runThreads(long timeout, TimeUnit unit) {
        if (timeout <= 0 || unit == null) {
            throw new IllegalArgumentException("Invalid timeout.");
        }
        run(timeout, unit);
    }

    private Number run(long timeout, TimeUnit unit) {
        synchronized (this) {
            awaitDispatchExit();
            if (dispatching && dispatchOwner != Thread.currentThread()) {
                throw new IllegalStateException("backend is already owned by another host thread");
            }
            dispatching = true;
            dispatchOwner = Thread.currentThread();
        }
        boolean normalExit = false;
        boolean mainCompleted = false;
        Number mainResult = null;
        Throwable dispatchFailure = null;
        try {
            long start = System.currentTimeMillis();
            while (true) {
                drainExternalTasks();
                promoteRunnableInvocations();
                if (taskList.isEmpty()) {
                    throw new IllegalStateException();
                }
                for (Iterator<Task> iterator = taskList.iterator(); iterator.hasNext(); ) {
                    Task task = iterator.next();
                    if (task.isFinish()) {
                        continue;
                    }
                    InvocationRecord invocation = invocationFor(task);
                    if (invocation != null && invocation.isTerminationRequested()) {
                        retireCancelledInvocation(iterator, task, invocation);
                        continue;
                    }
                    if (task.canDispatch()) {
                        if (invocation != null && !admitInvocation(invocation)) {
                            continue;
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("Start dispatch task=" + task);
                        }
                        TaskThreadBinding taskBinding = ensureThreadBinding(task);
                        emulator.set(Task.TASK_KEY, task);
                        this.runningThreadBinding = taskBinding;
                        this.runningAdmission = invocation == null
                                ? null : invocation.getAdmissionReceipt();

                        if(task.isContextSaved()) {
                            task.restoreContext(emulator);
                            for (SignalTask signalTask : task.getSignalTaskList()) {
                                if (signalTask.canDispatch()) {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Start run signalTask=" + signalTask);
                                    }
                                    SignalOps ops = task.isMainThread() ? this : task;
                                    try {
                                        this.runningTask = signalTask;
                                        Number ret = signalTask.callHandler(ops, emulator);
                                        if (log.isDebugEnabled()) {
                                            log.debug("End run signalTask=" + signalTask + ", ret=" + ret);
                                        }
                                        if (ret != null) {
                                            signalTask.setResult(emulator, ret);
                                            signalTask.destroy(emulator);
                                            task.removeSignalTask(signalTask);
                                        } else {
                                            signalTask.saveContext(emulator);
                                        }
                                    } catch (PopContextException e) {
                                        this.runningTask.popContext(emulator);
                                    }
                                } else if (log.isDebugEnabled()) {
                                    log.debug("Skip call handler signalTask=" + signalTask);
                                }
                            }
                        }

                        try {
                            this.runningTask = task;
                            this.runningInvocation = invocation;
                            Number ret = task.dispatch(emulator);
                            if (log.isDebugEnabled()) {
                                log.debug("End dispatch task=" + task + ", ret=" + ret);
                            }
                            if (ret != null) {
                                boolean terminationRequested = invocation != null
                                        && invocation.isTerminationRequested();
                                if (!terminationRequested) {
                                    task.setResult(emulator, ret);
                                }
                                destroyTask(task);
                                synchronized (this) {
                                    iterator.remove();
                                }
                                if (terminationRequested) {
                                    finishRequestedTermination(invocation);
                                } else {
                                    finishInvocation(invocation, InvocationResult.completed(ret));
                                }
                                if(task.isMainThread()) {
                                    mainCompleted = true;
                                    mainResult = ret;
                                    if (beginDispatchExitIfIdle()) {
                                        normalExit = true;
                                        return ret;
                                    }
                                    break;
                                }
                            } else {
                                if (invocation != null && invocation.isTerminationRequested()) {
                                    retireCancelledInvocation(iterator, task, invocation);
                                } else {
                                    task.saveContext(emulator);
                                    suspendInvocation(invocation, task);
                                }
                            }
                        } catch(PopContextException e) {
                            this.runningTask.popContext(emulator);
                        } catch (RuntimeException e) {
                            if (invocation != null) {
                                destroyTask(task);
                                finishInvocation(invocation, InvocationResult.fault(
                                        "dispatcher task failed", e));
                                iterator.remove();
                            } else {
                                throw e;
                            }
                        } finally {
                            this.runningInvocation = null;
                            this.runningAdmission = null;
                            this.runningThreadBinding = null;
                        }
                    } else {
                        if (log.isTraceEnabled() && task.isContextSaved()) {
                            task.restoreContext(emulator);
                            log.trace("Skip dispatch task=" + task);
                            emulator.getUnwinder().unwind();
                        } else if (log.isDebugEnabled()) {
                            log.debug("Skip dispatch task=" + task);
                        }
                    }
                }

                synchronized (this) {
                    Collections.reverse(threadTaskList);
                    for (Iterator<ThreadTask> iterator = threadTaskList.iterator(); iterator.hasNext(); ) {
                        taskList.add(0, iterator.next());
                        iterator.remove();
                    }
                }

                if (timeout > 0 && unit != null &&
                        System.currentTimeMillis() - start >= unit.toMillis(timeout)) {
                    synchronized (this) {
                        dispatchExiting = true;
                        normalExit = invocationByTask.isEmpty();
                    }
                    return mainCompleted ? mainResult : null;
                }
                if (taskList.isEmpty()) {
                    if (!externalTaskQueue.isEmpty()) {
                        continue;
                    }
                    if (!beginDispatchExitIfIdle()) {
                        continue;
                    }
                    normalExit = true;
                    return mainCompleted ? mainResult : null;
                }

                if (log.isDebugEnabled()) {
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (RuntimeException | Error e) {
            dispatchFailure = e;
            throw e;
        } finally {
            if (!normalExit) {
                synchronized (this) {
                    dispatchExiting = true;
                }
                Throwable failure = dispatchFailure == null
                        ? new IllegalStateException("dispatcher stopped before guest task completion")
                        : dispatchFailure;
                failInvocations(failure);
            }
            this.runningTask = null;
            this.runningInvocation = null;
            this.runningAdmission = null;
            this.runningThreadBinding = null;
            emulator.set(Task.TASK_KEY, null);
            synchronized (this) {
                dispatching = false;
                dispatchExiting = false;
                dispatchOwner = null;
                notifyAll();
            }
        }
    }

    private void drainExternalTasks() {
        Task task;
        List<Task> drained = new ArrayList<>();
        while ((task = externalTaskQueue.pollFirst()) != null) {
            drained.add(task);
        }
        for (int i = drained.size() - 1; i >= 0; i--) {
            Task candidate = drained.get(i);
            synchronized (this) {
                if (!taskList.contains(candidate) && !candidate.isFinish()) {
                    taskList.add(0, candidate);
                }
            }
        }
    }

    private synchronized InvocationRecord invocationFor(Task task) {
        return invocationByTask.get(task);
    }

    private InvocationRecord createInvocation(ThreadTask task, InvocationContext context,
                                              InvocationReferenceScope referenceScope) {
        TaskThreadBinding binding = ensureThreadBinding(task);
        InvocationRecord record = new InvocationRecord(++nextInvocationId, ++nextGeneration,
                Thread.currentThread(), task, context, runContext,
                binding.getGuestThread(), binding, referenceScope);
        invocationByTask.put(task, record);
        record.markQueued();
        invocationQueue.addLast(record);
        return record;
    }

    private synchronized boolean beginDispatchExitIfIdle() {
        if (!externalTaskQueue.isEmpty() || !invocationByTask.isEmpty()) {
            return false;
        }
        dispatchExiting = true;
        return true;
    }

    private void awaitDispatchExit() {
        while (dispatchExiting) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while waiting for backend ownership", e);
            }
        }
    }

    private boolean admitInvocation(InvocationRecord invocation) {
        AdmissionReceipt admission;
        synchronized (this) {
            if (invocation.getState() == InvocationRecord.State.ADMITTED) {
                return true;
            }
            if (invocationQueue.peekFirst() != invocation) {
                return false;
            }
            invocationQueue.removeFirst();
            admission = runContext.admitCarrier(invocation, invocation.getThreadBinding());
        }
        if (invocation.admit(admission)) {
            return true;
        }
        runContext.retireCarrier(admission);
        return false;
    }

    private void suspendInvocation(InvocationRecord invocation, Task task) {
        if (invocation == null || !invocation.suspend()) {
            return;
        }
        CarrierRetirementReceipt receipt = retireActiveCarrierLease(invocation);
        if (receipt != null) {
            invocation.recordCarrierRetirement(receipt);
        }
        if (task.canDispatch()) {
            synchronized (this) {
                invocationQueue.addLast(invocation);
            }
        }
    }

    private void promoteRunnableInvocations() {
        synchronized (this) {
            for (Task task : taskList) {
                InvocationRecord invocation = invocationByTask.get(task);
                if (invocation != null && invocation.getState() == InvocationRecord.State.SUSPENDED
                        && task.canDispatch() && !invocationQueue.contains(invocation)) {
                    invocation.markQueued();
                    invocationQueue.addLast(invocation);
                }
            }
        }
    }

    private void finishInvocation(InvocationRecord invocation, InvocationResult result) {
        if (invocation == null) {
            return;
        }
        CarrierRetirementReceipt receipt = retireActiveCarrierLease(invocation);
        invocation.complete(result);
        if (receipt == null) {
            invocation.markCarrierRetired();
        } else {
            invocation.markCarrierRetired(receipt);
        }
        synchronized (this) {
            invocationByTask.remove(invocation.getCarrier());
            invocationQueue.remove(invocation);
        }
    }

    private void retireCancelledInvocation(Iterator<Task> iterator, Task task,
                                           InvocationRecord invocation) {
        destroyTask(task);
        iterator.remove();
        finishRequestedTermination(invocation);
    }

    private void finishRequestedTermination(InvocationRecord invocation) {
        CarrierRetirementReceipt receipt = retireActiveCarrierLease(invocation);
        invocation.beginQuiescing();
        invocation.finishRequestedTermination();
        if (receipt == null) {
            invocation.markCarrierRetired();
        } else {
            invocation.markCarrierRetired(receipt);
        }
        synchronized (this) {
            invocationByTask.remove(invocation.getCarrier());
            invocationQueue.remove(invocation);
            externalTaskQueue.remove(invocation.getCarrier());
        }
    }

    private void failInvocations(Throwable failure) {
        List<InvocationRecord> records;
        synchronized (this) {
            records = new ArrayList<>(invocationByTask.values());
            for (InvocationRecord record : records) {
                taskList.remove(record.getCarrier());
                threadTaskList.remove(record.getCarrier());
                externalTaskQueue.remove(record.getCarrier());
            }
            invocationByTask.clear();
            invocationQueue.clear();
        }
        for (InvocationRecord record : records) {
            try {
                destroyTask(record.getCarrier());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            CarrierRetirementReceipt receipt = retireActiveCarrierLease(record);
            record.complete(InvocationResult.fault("dispatcher stopped", failure));
            if (receipt == null) {
                record.markCarrierRetired();
            } else {
                record.markCarrierRetired(receipt);
            }
        }
    }

    private CarrierRetirementReceipt retireActiveCarrierLease(InvocationRecord invocation) {
        AdmissionReceipt admission = invocation == null ? null : invocation.getAdmissionReceipt();
        if (admission == null || admission.getLease().isRetired()) {
            return null;
        }
        try {
            return runContext.retireCarrier(admission);
        } catch (RuntimeException e) {
            runContext.quarantine(e);
            throw e;
        }
    }

    private synchronized TaskThreadBinding ensureThreadBinding(Task task) {
        TaskThreadBinding binding = threadBindingByTask.get(task);
        if (binding != null && binding.isActive()) {
            return binding;
        }
        TaskThreadBinding taskBinding = task.getThreadBinding();
        if (taskBinding != null && taskBinding.isActive()
                && taskBinding.getRunContext() == runContext) {
            threadBindingByTask.put(task, taskBinding);
            return taskBinding;
        }
        String birthReason = task.isMainThread() ? "process-main" : "dispatcher-task";
        GuestThreadIncarnation thread = runContext.registerGuestThread(
                task.getId(), birthReason);
        binding = thread.bind(task);
        threadBindingByTask.put(task, binding);
        if (task instanceof AbstractTask) {
            ((AbstractTask) task).attachThreadBinding(binding);
        }
        return binding;
    }

    private synchronized void retireThreadBinding(Task task) {
        TaskThreadBinding binding = threadBindingByTask.remove(task);
        if (binding == null) {
            return;
        }
        runContext.retireGuestThread(binding.getGuestThread());
        if (task instanceof AbstractTask) {
            ((AbstractTask) task).detachThreadBinding();
        }
    }

    private void destroyTask(Task task) {
        try {
            task.destroy(emulator);
            for (SignalTask signalTask : task.getSignalTaskList()) {
                signalTask.destroy(emulator);
                task.removeSignalTask(signalTask);
            }
        } finally {
            retireThreadBinding(task);
        }
    }

    private void requestBackendStop() {
        if (!isDispatchingOnOtherThread()) {
            return;
        }
        try {
            emulator.getBackend().emu_stop();
        } catch (RuntimeException e) {
            log.warn("unable to request backend handoff", e);
        }
    }

    @Override
    public synchronized void dispose() {
        runContext.close();
        threadBindingByTask.clear();
        runningThreadBinding = null;
        runningAdmission = null;
    }

    @Override
    public synchronized int getTaskCount() {
        return taskList.size() + threadTaskList.size() + externalTaskQueue.size();
    }

    private SigSet mainThreadSigMaskSet;
    private SigSet mainThreadSigPendingSet;

    @Override
    public SigSet getSigMaskSet() {
        return mainThreadSigMaskSet;
    }

    @Override
    public void setSigMaskSet(SigSet sigMaskSet) {
        this.mainThreadSigMaskSet = sigMaskSet;
    }

    @Override
    public SigSet getSigPendingSet() {
        return mainThreadSigPendingSet;
    }

    @Override
    public void setSigPendingSet(SigSet sigPendingSet) {
        this.mainThreadSigPendingSet = sigPendingSet;
    }
}
