# LightBox-NG

A lightweight Android app virtualization tool inspired by VPhoneGaGa, supporting both **armv7** and **armv8** games in a single install, with a toggleable **virtual root** environment and **GameGuardian** support.

Forked from [LightBox-Source](https://github.com/RedClient231/LightBox-Source) (Apache 2.0), which derives from the BlackBox / VirtualApp lineage.

## What this is

LightBox-NG is an **in-process app sandbox**, not an OS emulator. It runs on stock, unrooted Android phones. It virtualizes individual apps inside its own process pool, using the Bcore engine (Dobby / JNI hooks / AIDL proxies) inherited from LightBox.

### What this is not

- Not a VM, not a hypervisor, not a Play Integrity bypass.
- Not a real root daemon — the virtual-root toggle only spoofs root signals inside the sandbox; the host device is unchanged.

## Feature delta vs. LightBox-Source v1.0.16

| Feature | LightBox v1.0.16 | LightBox-NG |
|---|---|---|
| Dual-ABI in one APK | No (two separate APKs, side-by-side) | **Yes** (`armv7` + `armv8` in one install) |
| Per-game ABI routing | Manual (pick correct APK) | **Automatic** (ABI-aware proxy pool) |
| Virtual root toggle | No | **Yes** (Settings -> Virtual Root) |
| GameGuardian support | Yes | Yes (preserved, not modified) |

## Status

**Experimental.** The ABI-aware proxy router and the virtual-root native layer are being wired incrementally. Current milestone: dual-ABI APK shipping (this build). Next milestone: ABI-aware proxy routing verified on a test device.

## License

Apache 2.0.
