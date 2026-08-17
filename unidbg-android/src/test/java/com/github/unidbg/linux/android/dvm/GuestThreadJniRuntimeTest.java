package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.InvocationContext;
import com.github.unidbg.thread.InvocationOutcome;
import com.github.unidbg.thread.ThreadTask;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Generic JNI contracts for guest-thread-owned attachment and exception state. */
public class GuestThreadJniRuntimeTest {

    @Test
    public void invocationArgumentsResolveDistinctGuestThreadJniEnvs() throws Exception {
        AndroidEmulator emulator = newEmulator();
        try {
            BaseVM vm = (BaseVM) emulator.createDalvikVM();
            long function = 0x100000000L;
            Backend backend = emulator.getBackend();
            backend.mem_map(function, 0x1000, 7);
            backend.mem_write(function, new byte[]{
                    (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6 // ret
            });

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Long> first = new AtomicReference<>();
            AtomicReference<Long> second = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread one = new Thread(() -> runJniEnvInvocation(
                    emulator, vm, function, ready, start, first, failure), "jni-env-one");
            Thread two = new Thread(() -> runJniEnvInvocation(
                    emulator, vm, function, ready, start, second, failure), "jni-env-two");

            one.start();
            two.start();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            one.join(3000);
            two.join(3000);

            assertNull(failure.get());
            assertTrue(!one.isAlive() && !two.isAlive());
            assertNotEquals(UnidbgPointer.nativeValue(vm.getJNIEnv()), first.get().longValue());
            assertNotEquals(UnidbgPointer.nativeValue(vm.getJNIEnv()), second.get().longValue());
            assertNotEquals(first.get(), second.get());
        } finally {
            emulator.close();
        }
    }

    @Test
    public void pendingExceptionIsResolvedFromCurrentGuestThread() throws Exception {
        AndroidEmulator emulator = newEmulator();
        try {
            BaseVM vm = (BaseVM) emulator.createDalvikVM();
            DvmObject<?> firstException = new StringObject(vm, "first");
            DvmObject<?> secondException = new StringObject(vm, "second");
            AtomicReference<DvmObject<?>> firstObserved = new AtomicReference<>();
            AtomicReference<DvmObject<?>> secondObserved = new AtomicReference<>();

            try (InvocationOutcome first = emulator.getThreadDispatcher().runThreadForOutcome(
                    new ExceptionTask(201, vm, firstException, firstObserved),
                    InvocationContext.builder().operation("jni-exception-one").build());
                 InvocationOutcome second = emulator.getThreadDispatcher().runThreadForOutcome(
                         new ExceptionTask(202, vm, secondException, secondObserved),
                         InvocationContext.builder().operation("jni-exception-two").build())) {
                assertEquals(Long.valueOf(0L), first.getValue());
                assertEquals(Long.valueOf(0L), second.getValue());
            }

            assertSame(firstException, firstObserved.get());
            assertSame(secondException, secondObserved.get());
            assertNull(vm.getPendingException());
        } finally {
            emulator.close();
        }
    }

    private static AndroidEmulator newEmulator() {
        return AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true))
                .setProcessName("guest-thread-jni-contract")
                .build();
    }

    private static void runJniEnvInvocation(
            AndroidEmulator emulator, BaseVM vm, long function,
            CountDownLatch ready, CountDownLatch start,
            AtomicReference<Long> result, AtomicReference<Throwable> failure) {
        try {
            ready.countDown();
            start.await();
            try (InvocationOutcome outcome = Module.emulateFunctionForOutcome(
                    emulator, function,
                    InvocationContext.builder().operation("jni-env-contract").build(),
                    null, new GuestThreadJniEnvNumber(vm, vm.getJNIEnv()))) {
                result.set(outcome.getValue().longValue());
            }
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }

    private static final class ExceptionTask extends ThreadTask {
        private final BaseVM vm;
        private final DvmObject<?> exception;
        private final AtomicReference<DvmObject<?>> observed;

        private ExceptionTask(int tid, BaseVM vm, DvmObject<?> exception,
                              AtomicReference<DvmObject<?>> observed) {
            super(tid, 0x1000);
            this.vm = vm;
            this.exception = exception;
            this.observed = observed;
        }

        @Override
        protected Number runThread(AbstractEmulator<?> emulator) {
            vm.throwException(exception);
            observed.set(vm.getPendingException());
            vm.clearPendingException();
            return 0L;
        }

        @Override
        protected String toThreadString() {
            return "jni-exception-contract";
        }
    }
}
