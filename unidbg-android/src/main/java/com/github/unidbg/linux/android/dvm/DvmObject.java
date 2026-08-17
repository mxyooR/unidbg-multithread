package com.github.unidbg.linux.android.dvm;

import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.linux.android.dvm.jni.ProxyDvmObject;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.thread.InvocationContext;
import com.github.unidbg.thread.InvocationOutcome;
import com.github.unidbg.thread.InvocationReferenceScope;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.List;

public class DvmObject<T> extends Hashable {

    private final DvmClass objectType;
    protected T value;
    private final BaseVM vm;

    protected DvmObject(DvmClass objectType, T value) {
        this(objectType == null ? null : objectType.vm, objectType, value);
    }

    private DvmObject(BaseVM vm, DvmClass objectType, T value) {
        this.vm = vm;
        this.objectType = objectType;
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    final void setValue(Object obj) {
        this.value = (T) obj;
    }

    public T getValue() {
        return value;
    }

    public DvmClass getObjectType() {
        return objectType;
    }

    protected boolean isInstanceOf(DvmClass dvmClass) {
        return objectType != null && objectType.isInstance(dvmClass);
    }

    @SuppressWarnings("unused")
    public void callJniMethod(Emulator<?> emulator, String method, Object...args) {
        if (objectType == null) {
            throw new IllegalStateException("objectType is null");
        }
        try (JniCallResult result = callJniMethodResult(
                emulator, vm, objectType, this, null, false, method, args)) {
            result.getValue();
        }
    }

    @SuppressWarnings("unused")
    public boolean callJniMethodBoolean(Emulator<?> emulator, String method, Object...args) {
        return BaseVM.valueOf(callJniMethodInt(emulator, method, args));
    }

    @SuppressWarnings("unused")
    public int callJniMethodInt(Emulator<?> emulator, String method, Object...args) {
        if (objectType == null) {
            throw new IllegalStateException("objectType is null");
        }
        try (JniCallResult result = callJniMethodResult(
                emulator, vm, objectType, this, null, false, method, args)) {
            return result.getValue().intValue();
        }
    }

    @SuppressWarnings("unused")
    public long callJniMethodLong(Emulator<?> emulator, String method, Object...args) {
        if (objectType == null) {
            throw new IllegalStateException("objectType is null");
        }
        try (JniCallResult result = callJniMethodResult(
                emulator, vm, objectType, this, null, false, method, args)) {
            return result.getValue().longValue();
        }
    }

    @SuppressWarnings("unused")
    public <V extends DvmObject<?>> V callJniMethodObject(Emulator<?> emulator, String method, Object...args) {
        if (objectType == null) {
            throw new IllegalStateException("objectType is null");
        }
        try (JniCallResult result = callJniMethodResult(
                emulator, vm, objectType, this, null, false, method, args)) {
            return result.resolveObject(objectType.vm);
        }
    }

    /** Runs a JNI method and returns its number with exact invocation evidence. */
    public JniInvocationOutcome<Number> callJniMethodOutcome(
            InvocationContext context, Emulator<?> emulator, String method, Object... args) {
        if (objectType == null) {
            throw new IllegalStateException("objectType is null");
        }
        try (JniCallResult result = callJniMethodResult(
                emulator, vm, objectType, this, context, true, method, args)) {
            Number value = result.getValue();
            return new JniInvocationOutcome<>(value, result.getInvocationOutcome());
        }
    }

    /** Resolves a JNI local object before acknowledging its exact invocation. */
    public <V extends DvmObject<?>> JniInvocationOutcome<V> callJniMethodObjectOutcome(
            InvocationContext context, Emulator<?> emulator, String method, Object... args) {
        if (objectType == null) {
            throw new IllegalStateException("objectType is null");
        }
        try (JniCallResult result = callJniMethodResult(
                emulator, vm, objectType, this, context, true, method, args)) {
            V value = result.resolveObject(objectType.vm);
            return new JniInvocationOutcome<>(value, result.getInvocationOutcome());
        }
    }

    protected static Number callJniMethod(Emulator<?> emulator, VM vm, DvmClass objectType,
                                          DvmObject<?> thisObj, String method, Object... args) {
        try (JniCallResult result = callJniMethodResult(
                emulator, vm, objectType, thisObj, null, false, method, args)) {
            return result.getValue();
        }
    }

    protected static JniCallResult callJniMethodResult(
            Emulator<?> emulator, VM vm, DvmClass objectType, DvmObject<?> thisObj,
            InvocationContext context, boolean forceInvocation,
            String method, Object... args) {
        if (!(vm instanceof BaseVM)) {
            throw new IllegalStateException("JNI calls require BaseVM");
        }
        BaseVM baseVm = (BaseVM) vm;
        boolean invocationOwned = forceInvocation
                || emulator.getThreadDispatcher().isBackendOwnedByAnotherThread();
        if (!invocationOwned) {
            JniCall call = prepareJniCall(emulator, vm, objectType, thisObj, null, method, args);
            Number value = Module.emulateFunction(emulator, call.function.peer,
                    call.arguments.toArray());
            return new JniCallResult(baseVm, value, null, null);
        }
        InvocationContext actualContext = context == null
                ? InvocationContext.builder().operation(method).origin("dvm-jni").build()
                : context;
        InvocationReferenceScope referenceScope = baseVm.createInvocationReferenceScope();
        try {
            JniCall call = prepareJniCall(emulator, vm, objectType, thisObj,
                    referenceScope, method, args);
            InvocationOutcome outcome = Module.emulateFunctionForOutcome(
                    emulator, call.function.peer, actualContext, referenceScope,
                    call.arguments.toArray());
            return new JniCallResult(baseVm, outcome.getValue(), outcome, referenceScope);
        } catch (RuntimeException | Error e) {
            referenceScope.acknowledgeOutcome();
            referenceScope.discardUnbound();
            throw e;
        }
    }

    private static JniCall prepareJniCall(
            Emulator<?> emulator, VM vm, DvmClass objectType, DvmObject<?> thisObj,
            InvocationReferenceScope referenceScope, String method, Object... args) {
        UnidbgPointer fnPtr = objectType.findNativeFunction(emulator, method);
        addPreparedLocalObject(vm, referenceScope, thisObj);
        List<Object> list = new ArrayList<>(10);
        list.add(vm.getJNIEnv());
        list.add(thisObj.hashCode());
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Boolean) {
                    list.add((Boolean) arg ? VM.JNI_TRUE : VM.JNI_FALSE);
                    continue;
                } else if(arg instanceof Hashable) {
                    list.add(arg.hashCode()); // dvm object

                    if(arg instanceof DvmObject) {
                        addPreparedLocalObject(vm, referenceScope, (DvmObject<?>) arg);
                    }
                    continue;
                } else if (arg instanceof DvmAwareObject ||
                        arg instanceof String ||
                        arg instanceof byte[] ||
                        arg instanceof short[] ||
                        arg instanceof int[] ||
                        arg instanceof float[] ||
                        arg instanceof double[] ||
                        arg instanceof Enum) {
                    DvmObject<?> obj = ProxyDvmObject.createObject(vm, arg);
                    list.add(obj.hashCode());
                    addPreparedLocalObject(vm, referenceScope, obj);
                    continue;
                }

                list.add(arg);
            }
        }
        return new JniCall(fnPtr, list);
    }

    private static void addPreparedLocalObject(VM vm, InvocationReferenceScope referenceScope,
                                               DvmObject<?> object) {
        if (referenceScope == null) {
            vm.addLocalObject(object);
        } else {
            ((BaseVM) vm).addInvocationLocalObject(referenceScope, object);
        }
    }

    private static final class JniCall {
        private final UnidbgPointer function;
        private final List<Object> arguments;

        private JniCall(UnidbgPointer function, List<Object> arguments) {
            this.function = function;
            this.arguments = arguments;
        }
    }

    protected static final class JniCallResult implements AutoCloseable {
        private final BaseVM vm;
        private final Number value;
        private final InvocationOutcome outcome;
        private final InvocationReferenceScope referenceScope;
        private boolean closed;

        private JniCallResult(BaseVM vm, Number value, InvocationOutcome outcome,
                              InvocationReferenceScope referenceScope) {
            this.vm = vm;
            this.value = value;
            this.outcome = outcome;
            this.referenceScope = referenceScope;
        }

        Number getValue() {
            requireCompleted();
            return value;
        }

        InvocationOutcome getInvocationOutcome() {
            if (outcome == null) {
                throw new IllegalStateException("JNI call has no invocation outcome");
            }
            requireCompleted();
            return outcome;
        }

        @SuppressWarnings("unchecked")
        <V extends DvmObject<?>> V resolveObject(BaseVM targetVm) {
            Number result = getValue();
            if (result == null) {
                return null;
            }
            return referenceScope == null
                    ? targetVm.getObject(result.intValue())
                    : (V) targetVm.getInvocationObject(referenceScope, result.intValue());
        }

        private void requireCompleted() {
            if (outcome == null || outcome.getResult().isCompleted()) {
                return;
            }
            String detail = outcome.getResult().getDetail();
            if (detail == null) {
                detail = "JNI invocation ended with " + outcome.getResult().getState();
            }
            throw new IllegalStateException(detail, outcome.getResult().getFault());
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (outcome != null) {
                outcome.acknowledgeOutcome();
            } else {
                vm.deleteLocalRefs();
            }
        }
    }

    @Override
    public String toString() {
        if (value instanceof Enum) {
            return value.toString();
        }

        if (objectType == null) {
            return getClass().getSimpleName() + "{" +
                    "value=" + value +
                    '}';
        }

        return objectType.getName() + "@" + Integer.toHexString(hashCode());
    }

    protected MemoryBlock memoryBlock;

    protected final UnidbgPointer allocateMemoryBlock(Emulator<?> emulator, int length) {
        if (memoryBlock == null) {
            memoryBlock = emulator.getMemory().malloc(length, true);
        }
        return memoryBlock.getPointer();
    }

    protected final void freeMemoryBlock(Pointer pointer) {
        if (this.memoryBlock != null && (pointer == null || this.memoryBlock.isSame(pointer))) {
            this.memoryBlock.free();
            this.memoryBlock = null;
        }
    }

    final void onDeleteRef() {
        freeMemoryBlock(null);
    }

}
