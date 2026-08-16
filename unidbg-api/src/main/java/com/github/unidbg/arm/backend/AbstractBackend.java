package com.github.unidbg.arm.backend;

public abstract class AbstractBackend implements Backend {

    @Override
    public void onInitialize() {
    }

    @Override
    public int getPageSize() {
        return 0;
    }

    @Override
    public void registerEmuCountHook(long emu_count) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEmuCountHookEnabled(boolean enabled) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean consumeEmuCountHookTriggered() {
        return false;
    }

    @Override
    public BackendStopReason getLastStopReason() {
        return BackendStopReason.NONE;
    }

    @Override
    public long getLastStopPc() {
        return 0;
    }

    @Override
    public void clearLastStopReason() {
    }

    @Override
    public boolean supportsNativeTimeslice() {
        return false;
    }

    @Override
    public void configureNativeTimeslice(long instructionBudget) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setNativeTimesliceEnabled(boolean enabled) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeJitCodeCache(long begin, long end) throws BackendException {
    }
}
