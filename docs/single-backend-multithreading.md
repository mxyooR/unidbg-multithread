# Single-backend multithreading

This experimental fork can run guest work submitted by multiple Java host
threads while keeping one emulator backend as the source of truth. The runtime
is generic: admission and ownership are based on invocation identity, not on a
library name, native command ID, or application workflow.

This is cooperative execution with preemption points, not simultaneous
execution of one Unicorn engine on multiple CPU cores.

## Execution model

1. The first caller that enters the dispatcher becomes the backend owner.
2. A call made by another host thread is represented by a `ThreadTask` and put
   into the external task queue.
3. The owner drains that queue, restores the selected task's saved CPU context,
   and runs the task on the backend.
4. A task yields through the existing context-switch, futex, signal, or native
   instruction-budget path. Its registers are saved and another runnable task
   is selected.
5. The submitting host thread receives the task result through a private future;
   it never invokes the backend directly. Owner selection and first submission
   are one atomic dispatcher operation, including when idle callers start at the
   same time.

The native Unicorn bridge records a stop reason and guest PC for each run. The
instruction-budget hook requests a stop from inside the code hook, while the
cross-thread stop flag provides a safe handoff for `emu_stop` requests.

## Invocation-Owned Runtime

Each tracked native entry has an `InvocationRecord` containing a unique ID,
generation, submitter identity, immutable `InvocationContext`, carrier task,
state, and private completion future. A timeslice, backend handoff, or futex wait
is non-terminal and cannot be published as a native return.

Terminal results are represented by `InvocationResult`:

- `COMPLETED` carries the exact return value, including a distinct null value.
- `FAULT` carries the original failure instead of converting it to `-1`.
- `TIMEOUT` and `CANCELLED` preserve the requesting invocation's identity.

`InvocationOutcome` couples that terminal to its owner. Callers must close the
outcome, or call `acknowledgeOutcome()`, after consuming the result. Temporary
resources are released only after both carrier retirement and outcome
acknowledgement.

The public generic entry points are:

- `Emulator.eFuncForOutcome`
- `Module.emulateFunctionForOutcome`
- `ThreadDispatcher.submitInvocation` and `runThreadForOutcome`
- `DvmObject.callJniMethodOutcome` and `callJniMethodObjectOutcome`
- `DvmClass.callStaticJniMethodOutcome` and
  `callStaticJniMethodObjectOutcome`

An owner can request cooperative termination through
`ThreadDispatcher.cancelInvocation` or `timeoutInvocation`. The request first
moves the invocation to quiescing; its terminal is published only after the
carrier has stopped and its backend context has been destroyed.

## Task-local state

`NativeWorkerTask32` and `NativeWorkerTask64` initialize arguments on a stack
allocated for that task. The task's register context and stack remain available
across a yield and are released when the task finishes. The process-loader stack
pointer is not changed by foreign-thread argument setup.

The `AddressedWaiter` contract keeps futex waiters associated with an exact guest
address and expected value. Wake-up code can therefore inspect all dispatcher
task sources, including tasks submitted from another host thread.

For invocation-owned Android JNI calls, local references are stored in a VM
scope belonging to that invocation. `PushLocalFrame`, `PopLocalFrame`, and
`DeleteLocalRef` operate on that scope. Pending JNI exceptions are isolated in
the same way, so one suspended call does not expose its local exception slot to
another call.

## Configuration

The native instruction budget is enabled only when the dispatcher has more than
one runnable source. Set `unidbg.nativeTimesliceBudget` as a Java system property
or environment value to change the default budget of `100000` guest instructions.
Values less than or equal to zero disable the native budget for that run.

Backends that do not implement native timeslice or exact stop-reason support
continue to use their existing behavior. The backend API supplies no-op
capability defaults so custom backends remain source-compatible. Exact
cross-thread stop evidence is currently verified with Unicorn2.

## Verified contracts

The repository contains generic tests with anonymous ARM64 instructions only:

- two host callers starting while the backend is idle acquire one backend owner;
- a foreign caller receives a turn before a long-running caller completes;
- register results remain isolated across suspend and restore;
- cross-thread `emu_stop` and native instruction budgets expose distinct stop
  reasons;
- cancellation, timeout, ownership mismatch, and two-latch reference cleanup
  preserve invocation identity.

## Limitations

- One backend is still serialized: this does not provide true parallel guest
  execution or multi-core memory consistency.
- Cancellation and timeout are cooperative. A backend or hook that cannot reach
  a supported stop point may delay carrier retirement.
- A synchronous guest re-entry from the dispatcher owner thread is rejected by
  `runThreadForResult`; callers should return to the dispatcher or submit work
  from another host thread.
- Backend hooks and emulator-wide loader state are shared resources. Code that
  mutates such state must do so while its task owns the backend.
- Invocation-scoped JNI local references and pending exceptions are covered;
  complete isolation of every emulator-global subsystem is not claimed.
- The Invocation-Owned Runtime APIs are under active development and may change
  before they are considered stable.
- Native binaries are platform-specific. The checked-in artifacts must match the
  corresponding JNI bridge and are not rebuilt by the Java Maven build.

## Extension points

Implementations may add another `ThreadTask` type for a generic guest entry,
then call `ThreadDispatcher.runThreadForOutcome`. The task must initialize all
guest-visible registers and use its own stack; it must not call the backend from
the submitting host thread. Application policy belongs above this runtime and
must not be encoded in dispatcher admission or terminal ownership.
