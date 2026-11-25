# Packaged local CLIs

Place Android CLI binaries under `bin/<abi>/` using AGP’s `lib*.so` naming so they are
extracted to `nativeLibraryDir` and can be exec’d by `ProcessBuilder` on Android 10+.

Prefer **PIE** (`ET_DYN`) binaries with interpreter `/system/bin/linker64` (same shape as
`libnode_cli.so`). Fully-static `ET_EXEC` builds have been observed to `SIGSEGV` on device
for some toolchains (notably static ffmpeg); BusyBox from Magisk NDK is `ET_EXEC` but is a
common Android-native build—verify on device after packaging.

`NodeBackend` sets `LD_LIBRARY_PATH` to `nativeLibraryDir` so dynamically linked CLIs can
resolve sibling `lib*.so` deps. Also set ELF `RUNPATH=$ORIGIN` via `set_runpath.py`.

Currently (arm64-v8a):

| Logical name | Packaged file | Notes |
| --- | --- | --- |
| `node` | `bin/arm64-v8a/libnode_cli.so` | Node.js CLI (PIE) |
| `ffmpeg` | `bin/arm64-v8a/libffmpeg.so` | PIE + shared libs (GPL, libx264) |
| `ffprobe` | `bin/arm64-v8a/libffprobe.so` | Same build as ffmpeg |
| `rg` | `bin/arm64-v8a/librg.so` | Termux ripgrep 15.x (needs `libpcre2-8.so`) |
| `busybox` | `bin/arm64-v8a/libbusybox.so` | Magisk NDK BusyBox 1.36.x |
| applets | same `libbusybox.so` | Nested `/system/bin/sh -c 'exec -a <filesDir>/agora-cli/busybox …'` (symlink argv0 for Magisk `realpath`; ELF still loaded from `libbusybox.so`): `tar` `awk` `sed` `find` `xargs` `diff` `xxd` `hexdump` `wc` `cut` `sort` `uniq` `gzip` `gunzip` |

Also ship ffmpeg Dynamic shared objects and `libpcre2-8.so` beside the CLIs.

## Node

```text
https://github.com/Delusions6515/node-android-build/releases/download/node-android-arm64-24/node-android-arm64-24.19.0.tar.xz
```

Extract `nodejs-v*-android-arm64/bin/node` → `libnode_cli.so`.

## FFmpeg / ffprobe (Dynamic GPL + x264, arm64)

From [rhythmcache/ffmpeg-android](https://github.com/rhythmcache/ffmpeg-android) **Dynamic**
build (not Static — Static is `ET_EXEC` and SIGSEGVs when exec’d from the app):

```text
https://github.com/rhythmcache/ffmpeg-android/releases/download/build-264/ffmpeg-8.0-ee2eb6c-Dynamic-android-arm64-v8a.zip
```

Unzip → extract inner `ffmpeg.tar.xz` → copy:

- `bin/ffmpeg` → `libffmpeg.so`
- `bin/ffprobe` → `libffprobe.so`
- `lib64/*.so` → same `bin/arm64-v8a/` folder

## ripgrep (`rg`)

Termux packages (Android aarch64 / Bionic):

```text
https://packages.termux.dev/apt/termux-main/pool/main/r/ripgrep/ripgrep_15.2.0_aarch64.deb
https://packages.termux.dev/apt/termux-main/pool/main/p/pcre2/pcre2_10.47_aarch64.deb
```

Extract `usr/bin/rg` → `librg.so`, `usr/lib/libpcre2-8.so` → `libpcre2-8.so`.

## BusyBox

Magisk NDK BusyBox (single arm64 binary):

```text
https://github.com/Magisk-Modules-Repo/busybox-ndk/releases/download/13614/UPDATE-Busybox.Installer.v1.36.1-ALL-signed.zip
```

Copy `busybox-arm64` → `libbusybox.so`.

## RUNPATH

```text
python app/libnode/set_runpath.py
```

The `bin/` tree is gitignored; rebuild the app after adding binaries.
