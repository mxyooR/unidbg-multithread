# Contributing

Thanks for helping improve the generic runtime. This repository is focused on
the reusable single-backend Guest Thread Runtime, not on a particular native
library or application workflow.

## Scope

Good contributions include:

- backend ownership, invocation lifecycle, guest-thread identity, and cleanup;
- ARM32/ARM64 generic runtime tests using anonymous instructions;
- backend capability and stop-reason compatibility;
- documentation, examples, and diagnostics that are independent of a target
  SO, command protocol, or readiness graph.

Please keep target-specific loaders, SO names, command IDs, protocol routes,
and business state in downstream projects.

## Before opening a pull request

Use the Maven wrapper and keep tests enabled explicitly because the historical
parent POM skips tests by default:

```text
./mvnw -pl unidbg-api -Dmaven.test.skip=false -DskipTests=false test
./mvnw -pl unidbg-android -am -Dmaven.test.skip=false -DskipTests=false \
  -DfailIfNoTests=false -Dtest=InvocationOwnedRuntimeTest,GuestThreadJniRuntimeTest test
./mvnw -pl unidbg-android -am -DskipTests \
  -Dmaven.javadoc.skip=true -Dgpg.skip=true package
```

On Windows, use `mvnw.cmd` with the same arguments. If a native backend is not
available on your platform, report the exact module and command that failed;
do not replace a runtime test with a business-specific fixture.

## Review expectations

Runtime changes should explain which ownership or lifecycle invariant they
preserve. Tests should assert exact invocation/guest-thread evidence where
possible, and documentation must distinguish serialized interleaving from
true multi-core execution. Keep public claims aligned with the capability
matrix in `docs/single-backend-multithreading.md`.
