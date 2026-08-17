package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.arm.ARM;
import com.github.unidbg.arm.FunctionCall;
import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import org.apache.commons.collections4.Bag;
import org.apache.commons.collections4.bag.HashBag;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import unicorn.Arm64Const;
import unicorn.ArmConst;

import java.util.Stack;

public abstract class BaseTask implements RunnableTask {

    private static final Log log = LogFactory.getLog(BaseTask.class);

    private Waiter waiter;

    @Override
    public void setWaiter(Emulator<?> emulator, Waiter waiter) {
        this.waiter = waiter;

        if (waiter != null &&
                log.isTraceEnabled()) {
            emulator.attach().debug();
        }
    }

    @Override
    public Waiter getWaiter() {
        return waiter;
    }

    @Override
    public final boolean canDispatch() {
        if (waiter != null) {
            return waiter.canDispatch();
        }
        return true;
    }

    private long context;

    @Override
    public final boolean isContextSaved() {
        return this.context != 0;
    }

    @Override
    public final void saveContext(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();
        if (this.context == 0) {
            this.context = backend.context_alloc();
        }
        backend.context_save(this.context);
        verifyStackState(emulator);
    }

    @Override
    public void popContext(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();
        int off = emulator.popContext();
        long pc;
        if (emulator.is32Bit()) {
            pc = backend.reg_read(ArmConst.UC_ARM_REG_PC).intValue() & 0xfffffffeL;
        } else {
            pc = backend.reg_read(Arm64Const.UC_ARM64_REG_PC).longValue();
        }
        backend.reg_write(emulator.is32Bit() ? ArmConst.UC_ARM_REG_PC : Arm64Const.UC_ARM64_REG_PC, pc + off);
        saveContext(emulator);
    }

    @Override
    public void restoreContext(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();
        backend.context_restore(this.context);
        verifyStackState(emulator);
    }

    protected final Number continueRun(AbstractEmulator<?> emulator, long until) {
        Backend backend = emulator.getBackend();
        backend.context_restore(this.context);
        verifyStackState(emulator);
        long pc;
        if (emulator.is32Bit()) {
            pc = backend.reg_read(ArmConst.UC_ARM_REG_PC).intValue() & 0xfffffffeL;
            if (ARM.isThumb(backend)) {
                pc |= 1;
            }
        } else {
            pc = backend.reg_read(Arm64Const.UC_ARM64_REG_PC).longValue();
        }
        if (log.isDebugEnabled()) {
            log.debug("continue run task=" + this + ", pc=" + UnidbgPointer.pointer(emulator, pc) + ", until=0x" + Long.toHexString(until));
        }
        Waiter waiter = getWaiter();
        if (waiter != null) {
            waiter.onContinueRun(emulator);
            setWaiter(emulator, null);
        }
        return emulator.emulate(pc, until);
    }

    @Override
    public void destroy(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();

        if (activeStackAllocation != null) {
            if (stackEvidence != null) {
                stackEvidence.requireCanaryIntact();
            } else if (stackRegion != null) {
                stackRegion.requireCanary(activeStackAllocation.getPointer().getLong(0));
            }
        }
        if (stackBlock != null) {
            stackBlock.free();
            stackBlock = null;
        }
        activeStackAllocation = null;
        stackRegion = null;

        if (this.context != 0) {
            backend.context_free(this.context);
            this.context = 0;
        }

        if (destroyListener != null) {
            destroyListener.onDestroy(emulator);
        }
    }

    public static final int THREAD_STACK_SIZE = 0x80000;

    private MemoryBlock stackBlock;
    private MemoryBlock activeStackAllocation;
    private StackRegion stackRegion;
    private long stackAllocationSequence;
    private TaskStackEvidence stackEvidence;

    protected final UnidbgPointer allocateStack(Emulator<?> emulator) {
        if (activeStackAllocation == null) {
            TaskThreadBinding binding = this instanceof Task
                    ? ((Task) this).getThreadBinding() : null;
            if (binding != null && binding.isActive()) {
                GuestThreadIncarnation guestThread = binding.getGuestThread();
                activeStackAllocation = guestThread.acquireStackAllocation(
                        emulator, THREAD_STACK_SIZE);
                stackRegion = guestThread.getStackRegion();
                stackAllocationSequence = guestThread.getStackAllocationSequence();
            } else {
                stackAllocationSequence = TaskStackEvidence.nextAllocationSequence();
                stackBlock = emulator.getMemory().malloc(
                        THREAD_STACK_SIZE + TaskStackEvidence.CANARY_SIZE, true);
                activeStackAllocation = stackBlock;
                stackRegion = TaskStackEvidence.initializeRegion(stackBlock,
                        THREAD_STACK_SIZE, stackAllocationSequence);
            }
        }
        return activeStackAllocation.getPointer().share(
                TaskStackEvidence.CANARY_SIZE + THREAD_STACK_SIZE, 0);
    }

    /** Captures the exact entry SP after arguments and ABI alignment are set. */
    protected final void captureStackEvidence(Emulator<?> emulator) {
        if (activeStackAllocation == null || stackRegion == null) {
            throw new IllegalStateException("task stack is not allocated");
        }
        long stackPointer = readStackPointer(emulator);
        TaskStackEvidence candidate = TaskStackEvidence.fromBackendAllocation(
                activeStackAllocation, stackRegion, stackPointer,
                stackAllocationSequence);
        if (stackEvidence != null
                && stackEvidence.getBackendAllocationIdentity()
                != candidate.getBackendAllocationIdentity()) {
            throw new IllegalStateException("task stack allocation identity changed");
        }
        stackEvidence = candidate;
    }

    public final TaskStackEvidence getStackEvidence() {
        return stackEvidence;
    }

    private void verifyStackState(Emulator<?> emulator) {
        if (activeStackAllocation == null || stackRegion == null) {
            return;
        }
        if (stackEvidence == null) {
            captureStackEvidence(emulator);
        } else {
            stackEvidence.requireStackPointer(readStackPointer(emulator));
        }
    }

    private static long readStackPointer(Emulator<?> emulator) {
        Backend backend = emulator.getBackend();
        return emulator.is32Bit()
                ? backend.reg_read(ArmConst.UC_ARM_REG_SP).intValue() & 0xffffffffL
                : backend.reg_read(Arm64Const.UC_ARM64_REG_SP).longValue();
    }

    @Override
    public void setResult(Emulator<?> emulator, Number ret) {
    }

    private DestroyListener destroyListener;

    @Override
    public void setDestroyListener(DestroyListener listener) {
        this.destroyListener = listener;
    }

    private final Stack<FunctionCall> stack = new Stack<>();
    private final Bag<Long> bag = new HashBag<>();

    @Override
    public void pushFunction(Emulator<?> emulator, FunctionCall call) {
        stack.push(call);
        bag.add(call.returnAddress, 1);

        if (log.isDebugEnabled()) {
            log.debug("pushFunction call=" + call.toReadableString(emulator) + ", bagCount=" + bag.getCount(call.returnAddress));
        }
    }

    @Override
    public FunctionCall popFunction(Emulator<?> emulator, long address) {
        if (!bag.contains(address)) {
            return null;
        }

        FunctionCall call;
        if (emulator.is64Bit()) { // check LR for aarch64
            call = stack.peek();
            long lr = emulator.getContext().getLR();
            if (lr != call.returnAddress) {
                return null;
            }

            bag.remove(address, 1);
            stack.pop();
        } else {
            bag.remove(address, 1);
            call = stack.pop();
        }

        if (log.isDebugEnabled()) {
            log.debug("popFunction call=" + call.toReadableString(emulator) + ", address=" + UnidbgPointer.pointer(emulator, address) + ", stackSize=" + stack.size() + ", bagCount=" + bag.getCount(address));
        }
        if (call.returnAddress != address) {
            for (FunctionCall fc : stack) {
                log.warn("stackCall call=" + fc.toReadableString(emulator) + ", bagCount=" + bag.getCount(fc.returnAddress));
            }
        }
        return call;
    }

    @Override
    public final String toString() {
        return getStatus() + "|" + toThreadString();
    }

    protected abstract String getStatus();
    protected abstract String toThreadString();

}
