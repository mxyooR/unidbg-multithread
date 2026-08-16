package com.github.unidbg.thread;

/** A waiter associated with an exact guest synchronization address. */
public interface AddressedWaiter extends Waiter {

    long getWaitAddress();

    int getExpectedValue();
}
