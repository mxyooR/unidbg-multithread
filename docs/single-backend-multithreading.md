# Single-backend multithreading

This experimental `v0.9.8` fork accepts guest work from multiple Java host
threads while keeping one emulator backend as the source of truth. Runtime
admission uses generic identities and evidence. It does not depend on a library
name, native command ID, or application workflow.

The Guest Thread Runtime migration is under development. One backend still
executes one carrier at a time; this is deterministic interleaving at explicit
stop and yield points, not simultaneous multi-core guest execution.

## Ownership model

The runtime separates four identities:

| Layer | Owns | Does not own |
| --- | --- | --- |
| `RunContext` | one emulator-run identity, guest-thread registry, active invocations, wait graph, fault and terminal evidence | application workflow |
| `GuestThreadIncarnation` | stable incarnation ID, guest TID, errno, `JNIEnv`, pending exception, thread attachments, invocation stack | one host Java thread |
| `InvocationRecord` | one call ID and generation, immutable context, completion, JNI local-reference scope, terminal | persistent guest-thread identity |
| `CarrierLease` | temporary permission to drive the backend, proven by admission and retirement receipts | guest TID or JNI identity |

A guest TID may be reused only after the old thread retires. The monotonically
increasing incarnation ID keeps the old and new threads distinct and prevents
TID-based ABA errors.

The current physical register context and worker stack remain owned by the
`Task`. Moving persistent stack ownership into `GuestThreadIncarnation` is a
remaining part of the migration.

## Dispatch and handoff

1. The first caller atomically becomes the backend owner and starts the
   dispatcher loop.
2. A call from another host thread becomes a `ThreadTask` with an exact
   `InvocationRecord` and enters the external queue.
3. The owner drains the queue, admits one carrier lease, restores that task's
   CPU context, and drives the backend.
4. A context switch, futex wait, signal path, backend stop, or native
   instruction budget can suspend the task. The lease retires before another
   invocation is admitted.
5. Completion, fault, timeout, or cancellation is published through the
   invocation's private future only after carrier retirement.

The submitting foreign host thread waits for its own outcome and never invokes
the backend directly. Idle owner selection and first submission are one
synchronized operation, including when two callers start at the same time.

Unicorn2 records a stop reason and guest PC for each run. Its instruction-budget
hook requests a timeslice stop from the code hook, while cross-thread stop
requests use the bridge's stop flag. Other backends retain their existing
behavior through optional backend capability methods.

## Invocation outcomes

`InvocationResult` distinguishes the following terminal states:

- `COMPLETED` carries the exact return value, including a distinct null value.
- `FAULT` retains the original failure.
- `TIMEOUT` and `CANCELLED` retain the requesting invocation's ownership.

`InvocationOutcome` couples a terminal to its exact invocation. Callers close
the outcome, or call `acknowledgeOutcome()`, after consuming it. An invocation
reference scope is released only after both carrier retirement and outcome
acknowledgement.

The public generic entry points are:

- `Emulator.eFuncForOutcome`
- `Module.emulateFunctionForOutcome`
- `ThreadDispatcher.submitInvocation` and `runThreadForOutcome`
- `DvmObject.callJniMethodOutcome` and `callJniMethodObjectOutcome`
- `DvmClass.callStaticJniMethodOutcome` and
  `callStaticJniMethodObjectOutcome`

Cancellation and timeout are cooperative. The request enters quiescing first;
the terminal is completed after the carrier stops and its task resources have
been retired.

## Guest-thread JNI state

`ThreadIdentityProvider` resolves the currently admitted guest thread instead
of using the host Java thread as guest identity. Each active guest thread has a
stable `JNIEnv` pointer and its own pending-exception slot. The 32-bit and
64-bit `AttachCurrentThread`, `DetachCurrentThread`, and `GetEnv` paths use that
guest-thread attachment.

JNI local references are intentionally invocation-scoped rather than
thread-scoped. `PushLocalFrame`, `PopLocalFrame`, and `DeleteLocalRef` therefore
operate on the active invocation scope, while `JNIEnv` and pending exceptions
remain stable across calls on the same guest thread.

## Wait, fault, and terminal evidence

`RunWaitGraph` publishes typed dependency batches atomically. Supported
dependency identities include exact invocations, futex address/value pairs,
guest-thread incarnations, callback mailboxes, and external resources. A batch
that introduces an invocation cycle is rejected without changing the graph
epoch or publishing a partial edge set.

When an invocation becomes terminal, both its outgoing and incoming invocation
edges are removed. `RootFaultController` first quarantines the run, which
freezes new admission, and only then snapshots transitive dependents while the
graph evidence is intact.

The run terminal ledger records immutable `InvocationEvidence`: run, guest
thread, invocation/generation, terminal, and all admission and retirement
receipts. This is runtime evidence, not an application readiness policy.

## Continuation status

`InvocationStack` enforces strict LIFO continuation ordering. The model carries
an explicit `ReentryMode`, exact parent continuation ID, guest-thread identity,
binding epoch, and stack depth. It rejects a child attached to the wrong parent,
thread, binding epoch, or depth, and rejects non-LIFO completion.

This contract is not yet a production nested-backend implementation. A
synchronous guest call submitted from the current dispatcher owner is still
rejected. Completing this path requires a real callback return boundary,
backend CPU snapshot, non-overlapping child ABI frame, and exact parent restore;
placing the child in the ordinary FIFO would deadlock behind its parent.

Same-thread synchronous re-entry, same-thread asynchronous mailbox work, and
cross-thread synchronous submission must remain distinct modes.

## Configuration

The native instruction budget is enabled only when the dispatcher has more than
one runnable source. Set `unidbg.nativeTimesliceBudget` as a Java system property
or environment value to change the default budget of `100000` guest
instructions. Values less than or equal to zero disable it for that run.

Exact stop-reason and cross-thread stop evidence is currently verified with
Unicorn2. Custom backends remain source-compatible through no-op capability
defaults.

## Verified contracts

The repository tests use anonymous ARM instructions and generic runtime tasks.
They verify:

- two simultaneous idle callers acquire exactly one backend owner;
- a foreign caller receives a turn and register results survive suspend/restore;
- guest threads have distinct live TIDs, incarnation IDs, and `JNIEnv` pointers;
- errno and pending JNI exceptions do not cross guest-thread boundaries;
- admission and retirement receipts balance in terminal evidence;
- cancellation, timeout, ownership mismatch, and two-latch reference cleanup;
- atomic multi-edge publication, whole-graph cycle rejection, terminal edge
  cleanup, root-fault ordering, and transitive dependent snapshots;
- strict LIFO parent/child continuation behavior;
- Unicorn2 timeslice and cross-thread stop reasons.

## Current limitations

- The single backend is serialized. There is no true parallel guest execution
  or multi-core memory-consistency model.
- Synchronous owner-thread guest re-entry has a model contract but no production
  nested-backend path and is rejected.
- Worker CPU contexts and physical stack allocations are task-owned, not yet
  persistent guest-thread-owned stack regions with canary evidence.
- Full pthread/TCB/TLS, signal, futex-owner, and thread-exit semantics are not
  yet represented by the guest-thread object.
- Cancellation depends on reaching a supported stop point. The current run
  close path does not claim a complete quiescence proof for every backend hook.
- Backend hooks, VM globals, loader state, memory, and file descriptors remain
  shared run resources. This fork does not claim isolation of every emulator
  subsystem.
- Exact stop-reason behavior has been exercised with Unicorn2; parity across all
  optional backends is not claimed.
- APIs and lifecycle contracts may change while the Guest Thread Runtime is
  under development.
- Checked-in native binaries are platform-specific and are packaged as-is by
  Maven; the Java build does not rebuild them.

## Extension boundary

A generic integration may implement another `ThreadTask` and call
`ThreadDispatcher.runThreadForOutcome`. It must initialize all guest-visible
registers, use an isolated task stack, and let the dispatcher own backend
admission. Application routing, command IDs, readiness rules, and SO-specific
state belong above this runtime and must not be added to dispatcher or guest
identity code.
