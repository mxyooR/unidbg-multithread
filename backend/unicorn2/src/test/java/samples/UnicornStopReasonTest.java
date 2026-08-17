package samples;

import com.github.unidbg.arm.backend.unicorn.CodeHook;
import com.github.unidbg.arm.backend.unicorn.Unicorn;
import org.junit.Test;
import unicorn.UnicornConst;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Backend-only contracts for cross-thread handoff and native timeslice evidence. */
public class UnicornStopReasonTest {

    static {
        try {
            org.scijava.nativelib.NativeLoader.loadLibrary("unicorn");
        } catch (IOException ignored) {
        }
    }

    @Test
    public void crossThreadStopIsReportedAsHandoff() throws Exception {
        Unicorn unicorn = new Unicorn(UnicornConst.UC_ARCH_ARM64, UnicornConst.UC_MODE_ARM);
        try {
            long address = 0x10000L;
            unicorn.mem_map(address, 0x1000, UnicornConst.UC_PROT_ALL);
            unicorn.mem_write(address, new byte[]{0, 0, 0, 20}); // b .
            CountDownLatch started = new CountDownLatch(1);
            unicorn.hook_add_new(new CodeHook() {
                @Override
                public void hook(Unicorn u, long pc, int size, Object user) {
                    started.countDown();
                }
            }, address, address, null);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try {
                    unicorn.emu_start(address, 0, 0, 0);
                } catch (Throwable e) {
                    failure.set(e);
                }
            }, "unicorn-stop-owner");
            owner.start();
            assertTrue("backend did not start", started.await(2, TimeUnit.SECONDS));
            unicorn.emu_stop();
            owner.join(2000);

            assertTrue("backend owner did not stop", !owner.isAlive());
            assertNull(failure.get());
            assertEquals(3, unicorn.getLastStopReason()); // STOP_EMU_STOP
        } finally {
            unicorn.closeAll();
        }
    }

    @Test
    public void nativeInstructionBudgetIsNonTerminalTimeslice() {
        Unicorn unicorn = new Unicorn(UnicornConst.UC_ARCH_ARM64, UnicornConst.UC_MODE_ARM);
        try {
            long address = 0x20000L;
            unicorn.mem_map(address, 0x1000, UnicornConst.UC_PROT_ALL);
            unicorn.mem_write(address, new byte[]{0, 0, 0, 20}); // b .
            unicorn.configureNativeTimeslice(1);
            unicorn.setNativeTimesliceEnabled(true);
            unicorn.emu_start(address, 0, 0, 0);
            assertEquals(2, unicorn.getLastStopReason()); // STOP_TIMESLICE
        } finally {
            unicorn.setNativeTimesliceEnabled(false);
            unicorn.closeAll();
        }
    }
}
