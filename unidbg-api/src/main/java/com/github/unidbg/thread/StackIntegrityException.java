package com.github.unidbg.thread;

/** Raised when a guest-thread stack no longer matches its physical evidence. */
public final class StackIntegrityException extends IllegalStateException {

    StackIntegrityException(String message) {
        super(message);
    }

    StackIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
