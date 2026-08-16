package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class InvocationRecordTest {

    @Test
    public void terminalAndReferenceLifetimeAreInvocationOwned() throws Exception {
        TestScope scope = new TestScope();
        InvocationRecord record = new InvocationRecord(
                1L, 1L, Thread.currentThread(), new NoopTask(),
                InvocationContext.builder()
                        .operation("test-entry")
                        .origin("contract-test")
                        .contextKey("one")
                        .build(), scope);

        assertEquals(InvocationRecord.State.RESERVED, record.getState());
        record.markQueued();
        assertTrue(record.admit());
        assertTrue(record.suspend());
        record.markQueued();
        assertTrue(record.admit());
        assertTrue(record.complete(InvocationResult.completed(7L)));
        record.markCarrierRetired();

        InvocationOutcome outcome = record.await(1, TimeUnit.SECONDS);
        assertNotNull(outcome);
        assertEquals(Long.valueOf(7L), outcome.getValue());
        assertEquals(1L, outcome.getResult().getOwnership().getInvocationId());
        assertFalse(scope.closed);

        outcome.acknowledgeOutcome();
        assertTrue(scope.closed);
    }

    @Test
    public void nonTerminalResultCannotBePublishedAsOutcome() {
        InvocationRecord record = new InvocationRecord(
                2L, 2L, Thread.currentThread(), new NoopTask(),
                InvocationContext.builder().operation("test-entry").build(), null);
        record.markQueued();
        assertTrue(record.admit());
        assertFalse(record.complete(InvocationResult.timeslice()));
        assertEquals(InvocationRecord.State.ADMITTED, record.getState());
    }

    private static final class NoopTask extends ThreadTask {
        private NoopTask() {
            super(100, 0x1000);
        }

        @Override
        protected Number runThread(AbstractEmulator<?> emulator) {
            return 0L;
        }

        @Override
        protected String toThreadString() {
            return "invocation-test";
        }
    }

    private static final class TestScope implements InvocationReferenceScope {
        private boolean bound;
        private boolean retired;
        private boolean acknowledged;
        private boolean closed;

        @Override
        public synchronized void bindToCarrier() {
            if (bound) {
                throw new IllegalStateException();
            }
            bound = true;
        }

        @Override
        public synchronized void markCarrierRetired() {
            retired = true;
            update();
        }

        @Override
        public synchronized void acknowledgeOutcome() {
            acknowledged = true;
            update();
        }

        @Override
        public synchronized void discardUnbound() {
            closed = true;
        }

        @Override
        public synchronized boolean isClosed() {
            return closed;
        }

        private void update() {
            closed = retired && acknowledged;
        }
    }
}
