package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Contract tests for generic guest-thread identity and carrier evidence. */
public class GuestThreadRuntimeTest {

    @Test
    public void admissionAndRetirementKeepThreadIdentityAcrossCarrierLease() {
        RunContext run = new RunContext();
        NoopTask task = new NoopTask(41);
        GuestThreadIncarnation thread = run.registerGuestThread(41, "contract-test");
        TaskThreadBinding binding = thread.bind(task);
        InvocationRecord record = new InvocationRecord(
                11L, 7L, Thread.currentThread(), task,
                InvocationContext.builder().operation("generic-entry").build(),
                run, thread, binding, null);
        record.markQueued();

        AdmissionReceipt admission = run.admitCarrier(record, binding);
        assertTrue(record.admit(admission));
        assertSame(thread, admission.getGuestThread());
        assertEquals(1, thread.getInvocationStack().depth());
        assertNotNull(record.getContinuation());

        CarrierRetirementReceipt retirement = run.retireCarrier(admission);
        record.markCarrierRetired(retirement);
        assertTrue(admission.getLease().isRetired());
        assertTrue(record.getRetirementReceipt().matches(admission));
        assertEquals(0, thread.getInvocationStack().depth());
        assertTrue(run.acceptsAdmission());
    }

    @Test
    public void bindingEpochChangesOnlyAfterExactBindingRetirement() {
        RunContext run = new RunContext();
        NoopTask task = new NoopTask(42);
        GuestThreadIncarnation thread = run.registerGuestThread(42, "epoch-test");
        TaskThreadBinding first = thread.bind(task);
        assertSame(first, thread.bind(task));
        assertEquals(1L, first.getEpoch());
        first.detach();
        TaskThreadBinding second = thread.bind(task);
        assertEquals(2L, second.getEpoch());
        assertFalse(first.isActive());
        assertTrue(second.isActive());
    }

    @Test
    public void quarantineRejectsNewAdmissionAndClosesThreadState() {
        RunContext run = new RunContext();
        GuestThreadIncarnation thread = run.registerGuestThread(43, "quarantine-test");
        thread.getExecutionState().setPendingException("pending");
        run.quarantine(new IllegalStateException("contract fault"));
        assertFalse(run.acceptsAdmission());
        run.close();
        assertTrue(thread.isRetired());
        assertEquals(GuestThreadExecutionState.State.RETIRED,
                thread.getExecutionState().getState());
        assertEquals(null, thread.getExecutionState().getPendingException());
    }

    private static final class NoopTask extends ThreadTask {
        private NoopTask(int tid) {
            super(tid, 0x1000);
        }

        @Override
        protected Number runThread(AbstractEmulator<?> emulator) {
            return 0L;
        }

        @Override
        protected String toThreadString() {
            return "guest-thread-contract";
        }
    }
}
