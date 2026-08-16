package com.github.unidbg.thread;

/**
 * Lifetime boundary for temporary references owned by one invocation.
 * Implementations are supplied by the VM layer; the dispatcher only controls
 * carrier retirement and outcome acknowledgement.
 */
public interface InvocationReferenceScope {

    void bindToCarrier();

    void markCarrierRetired();

    void acknowledgeOutcome();

    void discardUnbound();

    boolean isClosed();
}
