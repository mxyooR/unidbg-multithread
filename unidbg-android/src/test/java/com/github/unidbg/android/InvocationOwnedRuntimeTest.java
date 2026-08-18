package com.github.unidbg.android;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.CodeHook;
import com.github.unidbg.arm.backend.UnHook;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.thread.GuestThreadIncarnation;
import com.github.unidbg.thread.InvocationContext;
import com.github.unidbg.thread.InvocationOutcome;
import com.github.unidbg.thread.NativeWorkerTask32;
import com.github.unidbg.thread.NativeWorkerTask64;
import com.github.unidbg.thread.RootFaultController;
import com.github.unidbg.thread.RunContext;
import com.github.unidbg.thread.TaskStackEvidence;
import com.github.unidbg.thread.TaskThreadBinding;
import com.github.unidbg.thread.ThreadDispatcher;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Generic single-backend handoff test; it contains no library or application code. */
public class InvocationOwnedRuntimeTest {

    @Test
    public void simultaneousIdleCallersAcquireOneBackendOwner() throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .setProcessName("invocation-contract")
                .build();
        try {
            long function = 0x100000000L;
            Backend backend = emulator.getBackend();
            backend.mem_map(function, 0x1000, 7);
            backend.mem_write(function, new byte[]{
                    0x00, 0x04, 0x00, (byte) 0x91, // add x0, x0, #1
                    (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6 // ret
            });

            for (int i = 0; i < 100; i++) {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<Number> firstResult = new AtomicReference<>();
                AtomicReference<Number> secondResult = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread first = new Thread(() -> runConcurrentCall(
                        emulator, function, 10L, ready, start, firstResult, failure),
                        "idle-owner-one");
                Thread second = new Thread(() -> runConcurrentCall(
                        emulator, function, 20L, ready, start, secondResult, failure),
                        "idle-owner-two");

                first.start();
                second.start();
                assertTrue("callers did not become ready", ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                first.join(2000);
                second.join(2000);

                assertTrue("first caller did not finish", !first.isAlive());
                assertTrue("second caller did not finish", !second.isAlive());
                assertNull(failure.get());
                assertEquals(Long.valueOf(11L), firstResult.get());
                assertEquals(Long.valueOf(21L), secondResult.get());
            }
        } finally {
            emulator.close();
        }
    }

    @Test
    public void foreignHostCallGetsBackendTurnAndKeepsRegistersIsolated() throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .setProcessName("invocation-contract")
                .build();
        Thread first = null;
        Thread second = null;
        try {
            long loop = 0x100000000L;
            long quick = loop + 0x100L;
            Backend backend = emulator.getBackend();
            backend.mem_map(loop, 0x1000, 7);
            backend.mem_write(loop, new byte[]{
                    0x00, 0x04, 0x00, (byte) 0xf1, // subs x0, x0, #1
                    (byte) 0xe1, (byte) 0xff, (byte) 0xff, 0x54, // b.ne loop
                    (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6 // ret
            });
            backend.mem_write(quick, new byte[]{
                    0x00, 0x04, 0x00, (byte) 0x91, // add x0, x0, #1
                    (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6 // ret
            });

            CountDownLatch firstInstruction = new CountDownLatch(1);
            backend.hook_add_new(new CodeHook() {
                @Override
                public void hook(Backend ignored, long address, int size, Object user) {
                    firstInstruction.countDown();
                }

                @Override
                public void detach() {
                }

                @Override
                public void onAttach(UnHook unHook) {
                }
            }, loop, loop, null);

            AtomicReference<Number> firstResult = new AtomicReference<>();
            AtomicReference<Number> secondResult = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            first = new Thread(() -> {
                try {
                    firstResult.set(emulator.eFunc(loop, 100_000_000L));
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "invocation-owner");
            second = new Thread(() -> {
                try {
                    secondResult.set(emulator.eFunc(quick, 41L));
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "invocation-submitter");

            first.start();
            assertTrue("first invocation did not enter the backend",
                    firstInstruction.await(2, TimeUnit.SECONDS));
            second.start();
            second.join(3000);

            assertTrue("foreign invocation did not receive a backend turn", !second.isAlive());
            assertNull(failure.get());
            assertEquals(Long.valueOf(42L), secondResult.get());

            // The loop is intentionally long enough to exercise a handoff;
            // hosted Windows runners can be substantially slower than a
            // developer workstation while still making forward progress.
            first.join(30000);
            assertTrue("first invocation did not resume", !first.isAlive());
            assertEquals(Long.valueOf(0L), firstResult.get());
        } finally {
            if (first != null && first.isAlive()) {
                emulator.getBackend().emu_stop();
                first.join(5000);
            }
            if (second != null && second.isAlive()) {
                second.join(5000);
            }
            emulator.close();
        }
    }

    @Test
    public void explicitGuestThreadKeepsThreadStateAcrossInvocationCarriers() throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .setProcessName("persistent-guest-thread-contract")
                .build();
        try {
            long function = 0x100000000L;
            Backend backend = emulator.getBackend();
            backend.mem_map(function, 0x1000, 7);
            backend.mem_write(function, new byte[]{
                    0x00, 0x04, 0x00, (byte) 0x91, // add x0, x0, #1
                    (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6 // ret
            });

            ThreadDispatcher dispatcher = emulator.getThreadDispatcher();
            GuestThreadIncarnation guestThread = dispatcher.registerGuestThread(
                    0x7100, "explicit-runtime-contract");
            Object pendingException = new Object();
            guestThread.getExecutionState().setErrno(91);
            guestThread.getExecutionState().setPendingException(pendingException);

            NativeWorkerTask64 firstTask = new NativeWorkerTask64(
                    guestThread.getGuestTid(), function, emulator.getReturnAddress(),
                    false, 10L);
            TaskThreadBinding firstBinding = dispatcher.bindGuestThread(
                    guestThread, firstTask);
            try (InvocationOutcome first = dispatcher.runThreadForOutcome(firstTask,
                    InvocationContext.builder()
                            .operation("persistent-thread-first")
                            .origin("runtime-contract")
                            .build())) {
                assertEquals(Long.valueOf(11L), first.getValue());
                assertSame(guestThread, first.getInvocation().getGuestThread());
            }

            TaskStackEvidence firstStack = firstTask.getStackEvidence();
            assertNotNull(firstStack);
            assertSame(guestThread.getStackRegion(), firstStack.getRegion());
            assertTrue(firstStack.getRegion().contains(firstStack.getEntrySp()));
            assertEquals(firstStack.getCanaryReadBack(),
                    firstStack.readCanaryFromBackend());

            assertFalse(guestThread.isRetired());
            assertNull(guestThread.getActiveBinding());
            assertEquals(91, guestThread.getExecutionState().getErrno());
            assertSame(pendingException,
                    guestThread.getExecutionState().getPendingException());
            assertEquals(91, emulator.getMemory().getLastErrno());

            NativeWorkerTask64 secondTask = new NativeWorkerTask64(
                    guestThread.getGuestTid(), function, emulator.getReturnAddress(),
                    false, 20L);
            TaskThreadBinding secondBinding = dispatcher.bindGuestThread(
                    guestThread, secondTask);
            assertEquals(firstBinding.getEpoch() + 1L, secondBinding.getEpoch());
            try (InvocationOutcome second = dispatcher.runThreadForOutcome(secondTask,
                    InvocationContext.builder()
                            .operation("persistent-thread-second")
                            .origin("runtime-contract")
                            .build())) {
                assertEquals(Long.valueOf(21L), second.getValue());
                assertSame(guestThread, second.getInvocation().getGuestThread());
            }

            TaskStackEvidence secondStack = secondTask.getStackEvidence();
            assertNotNull(secondStack);
            assertSame(firstStack.getBackendAllocationIdentity(),
                    secondStack.getBackendAllocationIdentity());
            assertSame(firstStack.getRegion(), secondStack.getRegion());
            assertEquals(firstStack.getAllocationSequence(),
                    secondStack.getAllocationSequence());
            secondStack.requireCanaryIntact();

            assertFalse(guestThread.isRetired());
            assertNull(guestThread.getActiveBinding());
            assertEquals(91, guestThread.getExecutionState().getErrno());
            assertSame(pendingException,
                    guestThread.getExecutionState().getPendingException());

            dispatcher.retireGuestThread(guestThread);
            assertTrue(guestThread.isRetired());
        } finally {
            emulator.close();
        }
    }

    @Test
    public void explicitGuestThreadKeepsArm32StackAcrossInvocationCarriers()
            throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for32Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .setProcessName("persistent-guest-thread-arm32-contract")
                .build();
        try {
            long function = 0x10000000L;
            Backend backend = emulator.getBackend();
            backend.mem_map(function, 0x1000, 7);
            backend.mem_write(function, new byte[]{
                    0x01, 0x00, (byte) 0x80, (byte) 0xe2, // add r0, r0, #1
                    0x1e, (byte) 0xff, 0x2f, (byte) 0xe1 // bx lr
            });

            ThreadDispatcher dispatcher = emulator.getThreadDispatcher();
            GuestThreadIncarnation guestThread = dispatcher.registerGuestThread(
                    0x7300, "persistent-arm32-runtime-contract");
            NativeWorkerTask32 firstTask = new NativeWorkerTask32(
                    guestThread.getGuestTid(), function, emulator.getReturnAddress(),
                    false, 10L);
            dispatcher.bindGuestThread(guestThread, firstTask);
            try (InvocationOutcome first = dispatcher.runThreadForOutcome(firstTask,
                    InvocationContext.builder()
                            .operation("persistent-arm32-first")
                            .origin("runtime-contract")
                            .build())) {
                assertEquals(Long.valueOf(11L), first.getValue());
            }

            TaskStackEvidence firstStack = firstTask.getStackEvidence();
            assertNotNull(firstStack);
            NativeWorkerTask32 secondTask = new NativeWorkerTask32(
                    guestThread.getGuestTid(), function, emulator.getReturnAddress(),
                    false, 20L);
            TaskThreadBinding secondBinding = dispatcher.bindGuestThread(
                    guestThread, secondTask);
            assertEquals(2L, secondBinding.getEpoch());
            try (InvocationOutcome second = dispatcher.runThreadForOutcome(secondTask,
                    InvocationContext.builder()
                            .operation("persistent-arm32-second")
                            .origin("runtime-contract")
                            .build())) {
                assertEquals(Long.valueOf(21L), second.getValue());
            }

            TaskStackEvidence secondStack = secondTask.getStackEvidence();
            assertNotNull(secondStack);
            assertSame(firstStack.getBackendAllocationIdentity(),
                    secondStack.getBackendAllocationIdentity());
            secondStack.requireCanaryIntact();
            dispatcher.retireGuestThread(guestThread);
            assertTrue(guestThread.isRetired());
        } finally {
            emulator.close();
        }
    }

    @Test
    public void corruptedPersistentStackCanaryQuarantinesRunAndRejectsAdmission()
            throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .setProcessName("stack-integrity-contract")
                .build();
        try {
            long function = 0x100000000L;
            Backend backend = emulator.getBackend();
            backend.mem_map(function, 0x1000, 7);
            backend.mem_write(function, new byte[]{
                    0x00, 0x04, 0x00, (byte) 0x91,
                    (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6
            });

            ThreadDispatcher dispatcher = emulator.getThreadDispatcher();
            GuestThreadIncarnation guestThread = dispatcher.registerGuestThread(
                    0x7200, "stack-integrity-contract");
            NativeWorkerTask64 firstTask = new NativeWorkerTask64(
                    guestThread.getGuestTid(), function, emulator.getReturnAddress(),
                    false, 10L);
            dispatcher.bindGuestThread(guestThread, firstTask);
            try (InvocationOutcome first = dispatcher.runThreadForOutcome(firstTask,
                    InvocationContext.builder()
                            .operation("stack-integrity-first")
                            .origin("runtime-contract")
                            .build())) {
                assertTrue(first.getResult().isCompleted());
            }

            TaskStackEvidence evidence = firstTask.getStackEvidence();
            assertNotNull(evidence);
            MemoryBlock allocation = (MemoryBlock) evidence.getBackendAllocationIdentity();
            allocation.getPointer().setLong(0, 0L);

            NativeWorkerTask64 secondTask = new NativeWorkerTask64(
                    guestThread.getGuestTid(), function, emulator.getReturnAddress(),
                    false, 20L);
            dispatcher.bindGuestThread(guestThread, secondTask);
            try (InvocationOutcome second = dispatcher.runThreadForOutcome(secondTask,
                    InvocationContext.builder()
                            .operation("stack-integrity-second")
                            .origin("runtime-contract")
                            .build())) {
                assertTrue(second.getResult().isFault());
                assertNotNull(second.getResult().getFault());
            }

            RunContext runContext = dispatcher.getRunContext();
            assertEquals(RunContext.State.QUARANTINED, runContext.getState());
            assertTrue(runContext.getQuarantineCause() instanceof IllegalStateException);
            assertFalse(runContext.getRootFaultController().getLedgerSnapshot().isEmpty());
            RootFaultController.RootFaultPublication publication =
                    runContext.getRootFaultController().getLedgerSnapshot().get(0);
            assertEquals(RootFaultController.FaultKind.RUNTIME_INTEGRITY_FAULT,
                    publication.getKind());

            NativeWorkerTask64 rejectedTask = new NativeWorkerTask64(
                    guestThread.getGuestTid(), function, emulator.getReturnAddress(),
                    false, 30L);
            boolean rejected = false;
            try {
                dispatcher.bindGuestThread(guestThread, rejectedTask);
                dispatcher.runThreadForOutcome(rejectedTask,
                        InvocationContext.builder()
                                .operation("stack-integrity-rejected")
                                .origin("runtime-contract")
                                .build());
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            assertTrue("quarantined run accepted a new admission", rejected);
        } finally {
            emulator.close();
        }
    }

    private static void runConcurrentCall(
            AndroidEmulator emulator, long function, long argument,
            CountDownLatch ready, CountDownLatch start,
            AtomicReference<Number> result, AtomicReference<Throwable> failure) {
        try {
            ready.countDown();
            start.await();
            result.set(emulator.eFunc(function, argument));
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }
}
