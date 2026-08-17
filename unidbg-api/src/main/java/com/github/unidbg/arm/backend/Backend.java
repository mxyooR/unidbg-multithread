package com.github.unidbg.arm.backend;

import com.github.unidbg.debugger.BreakPoint;
import com.github.unidbg.debugger.BreakPointCallback;

public interface Backend {

    void onInitialize();

    void switchUserMode();
    void enableVFP();

    Number reg_read(int regId)throws BackendException;

    byte[] reg_read_vector(int regId) throws BackendException;
    void reg_write_vector(int regId, byte[] vector) throws BackendException;

    void reg_write(int regId, Number value) throws BackendException;

    byte[] mem_read(long address, long size) throws BackendException;

    void mem_write(long address, byte[] bytes) throws BackendException;

    void mem_map(long address, long size, int perms) throws BackendException;

    void mem_protect(long address, long size, int perms) throws BackendException;

    void mem_unmap(long address, long size) throws BackendException;

    BreakPoint addBreakPoint(long address, BreakPointCallback callback, boolean thumb);
    boolean removeBreakPoint(long address);
    void setSingleStep(int singleStep);
    void setFastDebug(boolean fastDebug);

    void removeJitCodeCache(long begin, long end) throws BackendException;

    void hook_add_new(CodeHook callback, long begin, long end, Object user_data) throws BackendException;

    void debugger_add(DebugHook callback, long begin, long end, Object user_data) throws BackendException;

    void hook_add_new(ReadHook callback, long begin, long end, Object user_data) throws BackendException;

    void hook_add_new(WriteHook callback, long begin, long end, Object user_data) throws BackendException;

    void hook_add_new(EventMemHook callback, int type, Object user_data) throws BackendException;

    void hook_add_new(InterruptHook callback, Object user_data) throws BackendException;

    void hook_add_new(BlockHook callback, long begin, long end, Object user_data) throws BackendException;

    void emu_start(long begin, long until, long timeout, long count) throws BackendException;

    void emu_stop() throws BackendException;

    void destroy() throws BackendException;

    void context_restore(long context);
    void context_save(long context);
    long context_alloc();
    void context_free(long context);

    int getPageSize();

    void registerEmuCountHook(long emu_count);

    /** Enables or disables the optional instruction-count hook. */
    void setEmuCountHookEnabled(boolean enabled);

    /** Returns and clears whether the count hook stopped the last run. */
    boolean consumeEmuCountHookTriggered();

    /** Returns the native reason for the last emulation stop. */
    BackendStopReason getLastStopReason();

    /** Returns the guest PC observed at the last native stop, when available. */
    long getLastStopPc();

    /** Clears the native stop metadata before a new scheduler-owned run. */
    void clearLastStopReason();

    /** Whether this backend reports an exact reason for every emulation stop. */
    default boolean supportsStopReason() {
        return false;
    }

    /** Whether this backend can stop at a guest-instruction budget. */
    boolean supportsNativeTimeslice();

    /** Configures the instruction budget used by native timeslice support. */
    void configureNativeTimeslice(long instructionBudget);

    /** Enables or disables native timeslice checks for subsequent runs. */
    void setNativeTimesliceEnabled(boolean enabled);

}
