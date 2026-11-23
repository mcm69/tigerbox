#!/bin/sh
# Build sn3199w for the tigerbox (RK3128, ARMv7, Android 5.1 = API 22).
# Dynamically linked against the box's own libc (API 22 stubs). Do NOT build
# with -static: the NDK's modern static bionic calls madvise(MADV_WIPEONFORK)
# at startup, which needs Linux 4.14+, and this box runs 3.10 - it aborts
# before main() with "arc4random data MADV_WIPEONFORK failed".
# Expect harmless "WARNING: linker: Unsupported flags DT_FLAGS_1 / unused DT
# entry" lines on every run: the 5.1 linker doesn't know newer ELF entries.
set -eu
cd "$(dirname "$0")"
NDK=${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370}
API=${API:-22}
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi$API-clang"
[ -x "$CC" ] || { echo "no compiler for API $API: $CC" >&2; exit 1; }
"$CC" -O2 -Wall -Wextra -o sn3199w sn3199w.c
"$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" sn3199w
file sn3199w
