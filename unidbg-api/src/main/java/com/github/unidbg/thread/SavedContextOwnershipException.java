package com.github.unidbg.thread;

/** Raised when a saved backend context no longer has its exact runtime owner. */
public final class SavedContextOwnershipException extends IllegalStateException {

    SavedContextOwnershipException(String message) {
        super(message);
    }
}
