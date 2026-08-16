package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.signal.SigSet;
import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.signal.UnixSigSet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * 抢占式调度
 */
public class UniThreadDispatcher implements ThreadDispatcher {

    private static final Log log = LogFactory.getLog(UniThreadDispatcher.class);

    private final List<Task> taskList = new ArrayList<>();
    private final AbstractEmulator<?> emulator;
    private final ConcurrentLinkedDeque<Task> externalTaskQueue = new ConcurrentLinkedDeque<>();
    private final Map<ThreadTask, CompletableFuture<Number>> externalResults =
            new IdentityHashMap<>();
    private volatile boolean dispatching;
    private volatile Thread dispatchOwner;

    public UniThreadDispatcher(AbstractEmulator<?> emulator) {
        this.emulator = emulator;
    }

    private final List<ThreadTask> threadTaskList = new ArrayList<>();

    @Override
    public void addThread(ThreadTask task) {
        synchronized (this) {
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

    /** Used by the emulator to decide whether a native instruction budget is useful. */
    public boolean hasMultipleRunnableSources() {
        return getTaskCount() > 1 || !externalTaskQueue.isEmpty();
    }

    public boolean hasExternalTasks() {
        return !externalTaskQueue.isEmpty();
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
        if (task == null) {
            throw new NullPointerException("task");
        }
        CompletableFuture<Number> result = new CompletableFuture<>();
        boolean queued;
        synchronized (this) {
            if (dispatching && dispatchOwner == Thread.currentThread()) {
                throw new IllegalStateException(
                        "synchronous guest re-entry on the backend owner thread is not supported");
            }
            externalResults.put(task, result);
            queued = dispatching;
            if (queued) {
                externalTaskQueue.addLast(task);
                notifyAll();
            } else {
                taskList.add(0, task);
            }
        }
        if (!queued) {
            run(0, null);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (;;) {
            try {
                return result.get(100, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (System.nanoTime() >= deadline) {
                    synchronized (this) {
                        externalTaskQueue.remove(task);
                        externalResults.remove(task);
                    }
                    throw new IllegalStateException("timed out waiting for guest task", e);
                }
                boolean takeOver;
                synchronized (this) {
                    takeOver = !dispatching && externalResults.containsKey(task);
                }
                if (takeOver) {
                    run(0, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for guest task", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new IllegalStateException("guest task failed", cause);
            }
        }
    }

    @Override
    public Number runMainForResult(MainTask main) {
        synchronized (this) {
            if (dispatching && dispatchOwner != Thread.currentThread()) {
                throw new IllegalStateException("backend is already owned by another host thread");
            }
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
            if (dispatching && dispatchOwner != Thread.currentThread()) {
                throw new IllegalStateException("backend is already owned by another host thread");
            }
            dispatching = true;
            dispatchOwner = Thread.currentThread();
        }
        boolean normalExit = false;
        try {
            long start = System.currentTimeMillis();
            while (true) {
                drainExternalTasks();
                if (taskList.isEmpty()) {
                    throw new IllegalStateException();
                }
                for (Iterator<Task> iterator = taskList.iterator(); iterator.hasNext(); ) {
                    Task task = iterator.next();
                    if (task.isFinish()) {
                        continue;
                    }
                    if (task.canDispatch()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Start dispatch task=" + task);
                        }
                        emulator.set(Task.TASK_KEY, task);

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
                            Number ret = task.dispatch(emulator);
                            if (log.isDebugEnabled()) {
                                log.debug("End dispatch task=" + task + ", ret=" + ret);
                            }
                            if (ret != null) {
                                task.setResult(emulator, ret);
                                task.destroy(emulator);
                                synchronized (this) {
                                    iterator.remove();
                                }
                                completeExternalResult(task, ret, null);
                                if(task.isMainThread()) {
                                    if (externalTaskQueue.isEmpty()) {
                                        normalExit = true;
                                        return ret;
                                    }
                                    break;
                                }
                            } else {
                                task.saveContext(emulator);
                            }
                        } catch(PopContextException e) {
                            this.runningTask.popContext(emulator);
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
                    normalExit = true;
                    return null;
                }
                if (taskList.isEmpty()) {
                    if (!externalTaskQueue.isEmpty()) {
                        continue;
                    }
                    normalExit = true;
                    return null;
                }

                if (log.isDebugEnabled()) {
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (RuntimeException | Error e) {
            failExternalResults(e);
            throw e;
        } finally {
            if (!normalExit) {
                failExternalResults(new IllegalStateException("dispatcher stopped before guest task completion"));
            }
            this.runningTask = null;
            emulator.set(Task.TASK_KEY, null);
            synchronized (this) {
                dispatching = false;
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

    private void completeExternalResult(Task task, Number result, Throwable failure) {
        if (!(task instanceof ThreadTask)) {
            return;
        }
        CompletableFuture<Number> future;
        synchronized (this) {
            future = externalResults.remove((ThreadTask) task);
        }
        if (future == null) {
            return;
        }
        if (failure == null) {
            future.complete(result);
        } else {
            future.completeExceptionally(failure);
        }
    }

    private void failExternalResults(Throwable failure) {
        List<CompletableFuture<Number>> futures;
        synchronized (this) {
            futures = new ArrayList<>(externalResults.values());
            externalResults.clear();
            externalTaskQueue.clear();
        }
        for (CompletableFuture<Number> future : futures) {
            future.completeExceptionally(failure);
        }
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
