package com.github.unidbg.thread;

import java.util.ArrayDeque;
import java.util.Deque;

/** Per-guest-thread logical invocation stack. */
public final class InvocationStack {

    private final Deque<InvocationContinuation> frames = new ArrayDeque<>();

    public synchronized void push(InvocationContinuation continuation) {
        if (continuation == null) {
            throw new NullPointerException("continuation");
        }
        if (!frames.contains(continuation)) {
            frames.push(continuation);
        }
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
