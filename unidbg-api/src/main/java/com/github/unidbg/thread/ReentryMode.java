package com.github.unidbg.thread;

/** Submission semantics; a mode never creates or changes guest-thread identity. */
public enum ReentryMode {
    FRESH_ASYNC,
    SAME_THREAD_SYNCHRONOUS,
    CROSS_THREAD_SYNCHRONOUS
}
