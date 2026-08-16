# Single-backend multi-threading

Unidbg can run guest work submitted by multiple Java host threads while keeping
one emulator backend as the source of truth. This is cooperative execution with
preemption points, not simultaneous execution of one Unicorn engine on multiple
CPU cores.

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
   it never invokes the backend directly. If the previous owner exits after its
   main task, a waiting submitter can take ownership and drain the queue.

The native Unicorn bridge records a stop reason and guest PC for each run. The
instruction-budget hook requests a stop from inside the code hook, while the
cross-thread stop flag provides a safe handoff for `emu_stop` requests.

## Task-local state

`NativeWorkerTask32` and `NativeWorkerTask64` initialize arguments on a stack
allocated for that task. The task's register context and stack remain available
across a yield and are released when the task finishes. The process-loader stack
pointer is not changed by foreign-thread argument setup.

The `AddressedWaiter` contract keeps futex waiters associated with an exact guest
address and expected value. Wake-up code can therefore inspect all dispatcher
task sources, including tasks submitted from another host thread.

## Configuration

The native instruction budget is enabled only when the dispatcher has more than
one runnable source. Set `unidbg.nativeTimesliceBudget` as a Java system property
or environment value to change the default budget of `100000` guest instructions.
Values less than or equal to zero disable the native budget for that run.

Backends that do not implement native timeslice support continue to use their
existing behavior. The backend API supplies no-op capability defaults so custom
backends remain source-compatible.

## Limitations

- One backend is still serialized: this does not provide true parallel guest
  execution or multi-core memory consistency.
- A synchronous guest re-entry from the dispatcher owner thread is rejected by
  `runThreadForResult`; callers should return to the dispatcher or submit work
  from another host thread.
- Backend hooks and emulator-wide loader state are shared resources. Code that
  mutates such state must do so while its task owns the backend.
- Native binaries are platform-specific. The checked-in artifacts must match the
  corresponding JNI bridge and are not rebuilt by the Java Maven build.

## Extension points

Implementations may add another `ThreadTask` type for a generic guest entry,
then call `ThreadDispatcher.runThreadForResult`. The task must initialize all
guest-visible registers and use its own stack; it must not call the backend from
the submitting host thread.
