package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        record.markCarrierRetired();
        assertTrue(record.complete(InvocationResult.completed(7L)));

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

    @Test
    public void cancellationQuiescesBeforePublishingTerminal() throws Exception {
        TestScope scope = new TestScope();
        InvocationRecord record = newRecord(3L, scope);
        record.markQueued();
        assertTrue(record.admit());

        assertTrue(record.requestCancellation("caller cancelled"));
        assertEquals(InvocationRecord.State.CANCEL_REQUESTED, record.getState());
        assertFalse(record.complete(InvocationResult.completed(9L)));
        assertFalse(record.finishRequestedTermination());
        assertTrue(record.beginQuiescing());
        record.markCarrierRetired();
        assertTrue(record.finishRequestedTermination());
        assertEquals(InvocationRecord.State.CANCELLED, record.getState());

        InvocationOutcome outcome = record.await(1, TimeUnit.SECONDS);
        assertTrue(outcome.getResult().isCancelled());
        assertEquals("caller cancelled", outcome.getResult().getDetail());
        assertFalse(scope.closed);
        outcome.acknowledgeOutcome();
        assertTrue(scope.closed);
    }

    @Test
    public void timeoutKeepsItsOwnTerminalKind() throws Exception {
        InvocationRecord record = newRecord(4L, null);
        record.markQueued();
        assertTrue(record.requestTimeout("deadline reached"));
        assertTrue(record.beginQuiescing());
        record.markCarrierRetired();
        assertTrue(record.finishRequestedTermination());

        InvocationOutcome outcome = record.await(1, TimeUnit.SECONDS);
        assertEquals(InvocationResult.State.TIMEOUT, outcome.getResult().getState());
        assertEquals(InvocationResult.Kind.TIMEOUT, outcome.getResult().getKind());
        assertEquals("deadline reached", outcome.getResult().getDetail());
    }

    @Test
    public void lateReferenceScopeReceivesBothReleaseLatches() throws Exception {
        InvocationRecord record = newRecord(5L, null);
        record.markQueued();
        assertTrue(record.admit());
        record.markCarrierRetired();
        assertTrue(record.complete(InvocationResult.completed(1L)));
        InvocationOutcome outcome = record.await(1, TimeUnit.SECONDS);
        outcome.acknowledgeOutcome();

        TestScope lateScope = new TestScope();
        assertTrue(record.installReferenceScope(lateScope));
        assertTrue(lateScope.bound);
        assertTrue(lateScope.closed);
    }

    @Test
    public void resultOwnedByAnotherInvocationIsRejected() {
        InvocationRecord record = newRecord(6L, null);
        record.markQueued();
        assertTrue(record.admit());
        record.markCarrierRetired();
        InvocationResult.Ownership other = new InvocationResult.Ownership(
                99L, 99L, Thread.currentThread().getId(), "other", "other-entry");
        InvocationResult foreign = InvocationResult.completed(3L).withOwnership(other);

        try {
            record.complete(foreign);
            fail("foreign result was accepted");
        } catch (IllegalArgumentException expected) {
            assertNull(record.getTerminal());
            assertEquals(InvocationRecord.State.ADMITTED, record.getState());
        }
    }

    @Test
    public void terminalCannotWakeCallerBeforeCarrierRetirement() throws Exception {
        InvocationRecord record = newRecord(7L, null);
        record.markQueued();
        assertTrue(record.admit());

        try {
            record.complete(InvocationResult.completed(5L));
            fail("terminal was published before carrier retirement");
        } catch (IllegalStateException expected) {
            assertFalse(record.isDone());
            assertNull(record.getTerminal());
        }

        record.markCarrierRetired();
        assertTrue(record.complete(InvocationResult.completed(5L)));
        InvocationOutcome outcome = record.await(1, TimeUnit.SECONDS);
        assertTrue(outcome.getInvocation().isCarrierRetired());
        assertEquals(Long.valueOf(5L), outcome.getValue());
    }

    private static InvocationRecord newRecord(long id, InvocationReferenceScope scope) {
        return new InvocationRecord(
                id, id, Thread.currentThread(), new NoopTask(),
                InvocationContext.builder().operation("test-entry").build(), scope);
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
