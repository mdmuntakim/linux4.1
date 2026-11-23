# Linux4.1

A genuine **Linux 4.1.52** kernel booting inside an on-device **QEMU** virtual
machine on Android **arm64**, with a landscape, immersive PC-style front end.

This is not a distro and not a compatibility layer.  The APK ships:

| Piece | Version | Source |
| --- | --- | --- |
| Kernel | Linux **4.1.52** (last 4.1 stable, EOL) | kernel.org tarball, SHA-256 pinned |
| Userspace | Alpine **3.20.0** minirootfs (musl + BusyBox + apk + e2fsprogs) | alpine CDN, SHA-256 pinned |
| Emulator | QEMU **5.0.0**, x86_64 guest, TCG multi-threaded | qemu.org tarball, SHA-256 pinned |
| App | Java, minSdk 26, targetSdk 34, arm64-v8a only | this repository |

"Linux 4.1" is a kernel series, not a distribution.  There is no original
Linux 4.1 OS to copy, so the rootfs is Alpine and the release notes say so.

## How it works

* `scripts/build-kernel.sh` builds upstream 4.1.52 with
  `kernel/linux41.fragment` on top of `x86_64_defconfig`: virtio-blk,
  virtio-net, ext4, devtmpfs, VGA/fbcon and bochs DRM built in, no modules.
* `scripts/build-rootfs.sh` builds a 192 MiB ext4 image from the Alpine
  minirootfs and installs an init that mounts `/data` (virtio-blk `/dev/vdb`)
  and starts a root shell on tty1 (VGA console) and ttyS0 (serial).
* `scripts/qemu-android-inner.sh` builds QEMU 5.0.0 statically for
  aarch64-musl inside an arm64 Alpine container.  The binary is packaged as
  `lib/arm64-v8a/libqemu-system-x86_64.so` so Android extracts it executable
  into `nativeLibraryDir`.  TCG is used: **there is no KVM on stock Android**.
* The app starts QEMU with `-display none -vnc 127.0.0.1:0`, then speaks RFB
  3.8 to it.  `RfbClient` is a small, dependency-free VNC client (Raw encoding,
  32-bit true colour).  `VncView` renders the framebuffer on a `SurfaceView`
  and maps touch to an absolute mouse; a trackpad mode, right-click emulation,
  wheel events, an on-screen keyboard and hardware keyboard support are
  included.
* The guest data disk is a sparse file created at the configured size.  The
  guest formats it on first boot (`mkfs.ext4`), so the size setting is honest
  and the disk survives app restarts.  "Reset disk" deletes it.

## Building

Requirements: Linux x86_64 with Docker, JDK 17, Android SDK, `mke2fs`, and
`sudo` for the rootfs chroot step.

```bash
bash scripts/build-kernel.sh      # out/bzImage, out/kernel.config
bash scripts/build-rootfs.sh      # out/rootfs.img, out/data.img
bash scripts/build-qemu-android.sh # out/qemu-android/ (arm64 container)
bash scripts/build-qemu-host.sh   # out/qemu-host/ (boot test)
bash scripts/boot-test.sh         # assertions + screenshots + measurements
bash scripts/build-apk.sh         # out/Linux4.1-arm64.apk (signed)
```

CI (`.github/workflows/build.yml`) does exactly this on GitHub Actions,
publishes `Linux4.1-arm64.apk`, the source archive, the pinned upstream
tarballs, screenshots and `SHA256SUMS.txt`, and puts the measured numbers in
the release notes.

## Acceptance status

The table is filled in by the release notes from `out/test-results.json`.
Anything that was not executed is reported as **not tested**, never as passed.

| Check | Status |
| --- | --- |
| Kernel is 4.1.x in the guest | verified by `uname -r` in CI boot test |
| Shell accepts commands | verified by `echo` round trip in CI |
| Boot in QEMU | verified on the x86_64 CI runner |
| Screenshots | captured from the real framebuffer by the app's RFB client |
| APK arm64-v8a only | asserted by `build-apk.sh` |
| Install + boot on a real arm64 Android device | **not tested** in CI |

The arm64 APK is built and signed in CI; the x86_64 runner cannot execute it.

## Signing

The release is signed with the project key in `app/keystore/linux41.jks`
(alias `linux41`, password `linux41`).  It is a project key, not a secret; it
is committed so the build is reproducible.  Do not use it for anything else.

## GPL

Linux and QEMU are GPLv2.  Every release carries the exact pinned upstream
source tarballs, this repository's source archive, the kernel config and the
build scripts.  See `NOTICE`.
