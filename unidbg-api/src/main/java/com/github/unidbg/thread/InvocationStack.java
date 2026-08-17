package com.github.unidbg.thread;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/** Per-guest-thread logical invocation stack. */
public final class InvocationStack {

    private final Deque<InvocationContinuation> frames = new ArrayDeque<>();

    public synchronized void push(InvocationContinuation continuation) {
        if (continuation == null) {
            throw new NullPointerException("continuation");
        }
        if (frames.contains(continuation)) {
            if (frames.peek() != continuation) {
                throw new IllegalStateException("continuation resume is not LIFO");
            }
            return;
        }
        if (!frames.isEmpty() || continuation.getReentryMode() != ReentryMode.FRESH_ASYNC
                || continuation.getParentContinuationId() != 0L) {
            throw new IllegalStateException(
                    "fresh continuation cannot bypass an active invocation");
        }
        frames.push(continuation);
    }

    public synchronized void pushSynchronousChild(InvocationContinuation parent,
                                                  InvocationContinuation child) {
        if (parent == null || child == null) {
            throw new NullPointerException("parent and child continuations");
        }
        int expectedDepth = parent.getStackDepth() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : parent.getStackDepth() + 1;
        if (frames.peek() != parent
                || child.getReentryMode() != ReentryMode.SAME_THREAD_SYNCHRONOUS
                || child.getParentContinuationId() != parent.getContinuationId()
                || child.getGuestThread() != parent.getGuestThread()
                || child.getBindingEpoch() != parent.getBindingEpoch()
                || child.getStackDepth() != expectedDepth
                || frames.contains(child)) {
            throw new IllegalStateException("invalid synchronous child continuation");
        }
        frames.push(child);
    }

    public synchronized InvocationContinuation completeSynchronousChild(
            InvocationContinuation child) {
        if (child == null || frames.peek() != child
                || child.getReentryMode() != ReentryMode.SAME_THREAD_SYNCHRONOUS) {
            throw new IllegalStateException("synchronous child completion is not LIFO");
        }
        Iterator<InvocationContinuation> iterator = frames.iterator();
        iterator.next();
        InvocationContinuation parent = iterator.hasNext() ? iterator.next() : null;
        if (parent == null
                || parent.getContinuationId() != child.getParentContinuationId()) {
            throw new IllegalStateException("synchronous child lost its exact parent");
        }
        frames.pop();
        return parent;
    }

    public synchronized boolean pop(InvocationContinuation continuation) {
        if (continuation == null || frames.isEmpty() || frames.peek() != continuation) {
            return false;
        }
        frames.pop();
        return true;
    }

    public synchronized InvocationContinuation peek() {
        return frames.peek();
    }

    public synchronized int depth() {
        return frames.size();
    }

    public synchronized boolean contains(InvocationContinuation continuation) {
        return frames.contains(continuation);
    }

    synchronized void clear() {
        frames.clear();
    }
}
