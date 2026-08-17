# unidbg-multithreading

Allows you to emulate an Android native library, and an experimental iOS emulation.<br>

This project is an experimental `v0.9.8` fork of unidbg for generic,
single-backend multithreading. It adds an Invocation-Owned Runtime without
embedding application-specific SO names, command IDs, routers, or assets.

## Development status

The Invocation-Owned Runtime is under active development. The current branch
implements serialized backend ownership, foreign host-thread submission,
task-local register contexts and stacks, exact invocation terminals, and
invocation-scoped JNI local references and pending exceptions. APIs and edge
case behavior may still change while cancellation, backend coverage, and JNI
contracts are expanded.

This is not simultaneous execution of one Unicorn engine on multiple CPU cores.
Guest calls take turns on one backend at explicit yield and stop points.

This is an educational project to learn more about the ELF/MachO file format and ARM assembly.<br>

Use it at your own risk !

## License
- unidbg uses software libraries from [Apache Software Foundation](http://apache.org). 

Simple tests under src/test directory
- [unidbg-android/src/test/java/com/bytedance/frameworks/core/encrypt/TTEncrypt.java](https://github.com/zhkl0228/unidbg/blob/master/unidbg-android/src/test/java/com/bytedance/frameworks/core/encrypt/TTEncrypt.java)  

![](assets/TTEncrypt.gif)
***
- [unidbg-android/src/test/java/com/sun/jna/JniDispatch32.java](https://github.com/zhkl0228/unidbg/blob/master/unidbg-android/src/test/java/com/sun/jna/JniDispatch32.java)  
![](assets/JniDispatch32.gif)
***
- [unidbg-android/src/test/java/com/sun/jna/JniDispatch64.java](https://github.com/zhkl0228/unidbg/blob/master/unidbg-android/src/test/java/com/sun/jna/JniDispatch64.java)  
![](assets/JniDispatch64.gif)
***
- [unidbg-android/src/test/java/org/telegram/messenger/Utilities32.java](https://github.com/zhkl0228/unidbg/blob/master/unidbg-android/src/test/java/org/telegram/messenger/Utilities32.java)  
![](assets/Utilities32.gif)
***
- [unidbg-android/src/test/java/org/telegram/messenger/Utilities64.java](https://github.com/zhkl0228/unidbg/blob/master/unidbg-android/src/test/java/org/telegram/messenger/Utilities64.java)  
![](assets/Utilities64.gif)

## More tests
- [unidbg-android/src/test/java/com/github/unidbg/android/QDReaderJni.java](https://github.com/zhkl0228/unidbg/blob/master/unidbg-android/src/test/java/com/github/unidbg/android/QDReaderJni.java)
- [unidbg-android/src/test/java/com/anjuke/mobile/sign/SignUtil.java](https://github.com/zhkl0228/unidbg/blob/master/unidbg-android/src/test/java/com/anjuke/mobile/sign/SignUtil.java)

## Features
- Emulation of the JNI Invocation API so JNI_OnLoad can be called.
- Support JavaVM, JNIEnv.
- Emulation of syscalls instruction.
- Support ARM32 and ARM64.
- Inline hook, thanks to [Dobby](https://github.com/jmpews/Dobby).
- Android import hook, thanks to [xHook](https://github.com/iqiyi/xHook).
- iOS [fishhook](https://github.com/facebook/fishhook) and substrate and [whale](https://github.com/asLody/whale) hook.
- [unicorn](https://github.com/zhkl0228/unicorn) backend support simple console debugger, gdb stub, instruction trace, memory read/write trace.
- Support iOS objc and swift runtime.
- Support [dynarmic](https://github.com/MerryMage/dynarmic) fast backend.
- Support Apple M1 hypervisor, the fastest ARM64 backend.
- Support Linux KVM backend with Raspberry Pi B4.
- Experimental Invocation-Owned Runtime for ARM32 and ARM64. Multiple host
  threads can submit guest calls to one dispatcher-owned backend with per-call
  identity, terminal evidence, CPU context, stack, and JNI reference scope.

See [docs/single-backend-multithreading.md](docs/single-backend-multithreading.md)
for the execution model, configuration, and limitations.

## Design references

- [Single-backend multithreading architecture notes](https://bbs.kanxue.com/thread-292140.htm)
- [Invocation-owned runtime evolution notes](https://bbs.kanxue.com/thread-292016.htm)

The Unicorn JNI bridge uses the checked-in platform binaries under
`backend/unicorn2/src/main/resources/natives`. The Java build packages these
artifacts as-is; rebuilding the native library is not part of the Maven build.

## Thanks
- [unicorn](https://github.com/zhkl0228/unicorn)
- [dynarmic](https://github.com/MerryMage/dynarmic)
- [HookZz](https://github.com/jmpews/Dobby)
- [xHook](https://github.com/iqiyi/xHook)
- [AndroidNativeEmu](https://github.com/AeonLucid/AndroidNativeEmu)
- [usercorn](https://github.com/lunixbochs/usercorn)
- [keystone](https://github.com/keystone-engine/keystone)
- [capstone](https://github.com/aquynh/capstone)
- [idaemu](https://github.com/36hours/idaemu)
- [jelf](https://github.com/fornwall/jelf)
- [whale](https://github.com/asLody/whale)
- [kaitai_struct](https://github.com/kaitai-io/kaitai_struct)
- [fishhook](https://github.com/facebook/fishhook)
- [runtime_class-dump](https://github.com/Tyilo/runtime_class-dump)
- [mman-win32](https://github.com/mcgarrah/mman-win32)

## Stargazers over time

[![Stargazers over time](https://starchart.cc/zhkl0228/unidbg.svg)](https://starchart.cc/zhkl0228/unidbg)

