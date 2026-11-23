# Linux4.1

A genuine **Linux 4.1.52** kernel built from kernel.org source, with its own
userspace (musl + BusyBox + a small framebuffer desktop) built from source, and
QEMU built from source to run it.  Everything here is reproducible from the
scripts in this repository.

The kernel reports:

```
# uname -r
4.1.52
```

and CI refuses to publish anything unless a real QEMU boot of the built kernel
prints a `uname -r` that starts with `4.1`.

## What this is, and what it is not

**This is a virtual machine.**  The Linux 4.1 stack runs inside QEMU, on top of
whatever host OS the device already runs.  On a phone that means Android stays
the operating system; Linux 4.1 is a guest.

**An APK cannot replace Android.**  Replacing the phone OS with bare-metal
Linux 4.1 is a device port, not an app: it needs an unlocked bootloader, a
device tree for that exact SoC, drivers for its display/touch/modem/storage, and
a boot image signed the way that device requires.  No universal APK can do
that.  See [docs/BARE-METAL.md](docs/BARE-METAL.md) for what a port actually
takes.

**The x86_64 guest is not shipped as "Linux on a phone".**  The benchmark in
[docs/BENCHMARKS.md](docs/BENCHMARKS.md) compares an aarch64 guest and an
x86_64 guest with the same workload on the same host, and the arm64 guest is the
one that ships for Android use.  Numbers are measured, never invented.

## Layout

```
kernel/patches/     the patch series that makes 4.1.52 build with a modern toolchain
kernel/configs/     guest kernel config fragments (arm64 and x86_64)
userspace/desktop/  the framebuffer desktop + benchmark program (C, musl static)
userspace/init      the PID 1 script (BusyBox ash)
scripts/            fetch, build, boot-test, benchmark, screenshot
docs/               architecture, benchmarks, bare-metal notes, APK status
```

## Build it

Host requirements: `gcc`, `gcc-aarch64-linux-gnu`, `make`, `bc`, `bison`,
`flex`, `cpio`, `xz`, `python3`, `ninja`, `pkg-config`, `libglib2.0-dev`,
`libpixman-1-dev`, `zlib1g-dev`, `libslirp-dev`, `device-tree-compiler`.

```bash
scripts/fetch-sources.sh            # kernel.org, musl, busybox, qemu tarballs + sha256
scripts/build-qemu.sh               # QEMU 9.2.3 from source, aarch64 + x86_64 TCG
scripts/build-kernel.sh arm64       # 4.1.52 Image + patches + config fragment
scripts/build-kernel.sh x86_64      # 4.1.52 bzImage
scripts/build-userspace.sh arm64    # musl + busybox + fbdesk -> initramfs
scripts/build-userspace.sh x86_64
scripts/boot-test.sh arm64          # boots, asserts uname -r starts with 4.1
scripts/boot-test.sh x86_64
scripts/bench.sh                    # FPS / boot time / input latency, both guests
scripts/screenshot.sh arm64 out/screenshots
```

Outputs land in `out/` (never committed; the `.gitignore` keeps binaries and
archives out of the source tree).

## The kernel patches

Linux 4.1.52 is from 2017 and does not build with GCC 13/14/15 as-is.  The
series is small and each patch fixes one real breakage:

| Patch | Why |
| --- | --- |
| `0001` host tools `-fcommon` | modern GCC defaults to `-fno-common`; the bundled `dtc` had multiple-definition `yylloc` |
| `0002` arm64 `proc.S` section flags | binutils no longer accepts `#alloc, #execinstr` |
| `0003` x86 realmode `-std=gnu89` | GCC 15 defaults to C23, where `bool`/`false` are keywords |
| `0004` x86 decompressor `-std=gnu89` | same, for `arch/x86/boot/compressed` |

`CONFIG_KVM` is disabled in the guest config: 4.1's arm64 KVM assembly uses
`teecr32_el1`, which current binutils no longer knows, and a TCG guest does not
need nested KVM.  This is the only feature the guest gives up.

## Licence

The kernel and QEMU are GPL-2.0; the scripts, the init script and the desktop
program in this repository are GPL-2.0 as well.  See `LICENSE`.

Linux is a registered trademark of Linus Torvalds.
