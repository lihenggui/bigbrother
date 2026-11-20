#!/usr/bin/env bash
#
# Copyright (C) 2026 Mercury Li
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 3 as
# published by the Free Software Foundation.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.

# Cross-compile bbd (with the eBPF object embedded) for every architecture
# the module supports, then build the APK, which is both the app and the
# flashable root module.
#
# Requires: rustup targets aarch64-linux-android and x86_64-linux-android,
# nightly toolchain with rust-src, bpf-linker in PATH, an Android NDK, a JDK
# and the Android SDK.
set -euo pipefail

cd "$(dirname "$0")/.."

# The architectures the module supports, and the one place they are listed:
# Gradle packages every out/bbd-<arch>, and the installer accepts a device
# whose $ARCH has a daemon in the archive. arm64 is what devices run; x86_64
# is what the Android emulator and Chromebooks run. GKI 2.0 kernels are
# 64-bit only, so there is no 32-bit build.
TARGETS=(aarch64-linux-android x86_64-linux-android)

# Rust target -> the architecture name module installers set in $ARCH.
arch_of() {
    case "$1" in
    aarch64-*) echo arm64 ;;
    x86_64-*) echo x64 ;;
    *) die "no module architecture name for $1" ;;
    esac
}
API=30 # Android 11; GKI 2.0 devices ship Android 12+, so this is safely low.

die() {
    echo "error: $*" >&2
    exit 1
}

# The Android SDK, for Gradle and as the default place to look for the NDK.
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[[ -d "$ANDROID_HOME" ]] || die "Android SDK not found at $ANDROID_HOME; set ANDROID_HOME"

# The NDK: $ANDROID_NDK_HOME if set, else the newest one in the SDK.
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "${ANDROID_NDK_HOME:-}" ]] || die "Android NDK not found; set ANDROID_NDK_HOME"

host_tag="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
command -v bpf-linker >/dev/null || die "bpf-linker not found; install it with: cargo install bpf-linker"

target_args=()
for target in "${TARGETS[@]}"; do
    clang="$bin/${target}${API}-clang"
    [[ -x "$clang" ]] || die "no NDK toolchain for $target at $bin"
    # cargo and the cc crate take the linker and compilers from variables
    # named after the target. (tr rather than ${var^^}: macOS ships bash 3.)
    target_env="${target//-/_}"
    export "CARGO_TARGET_$(echo "$target_env" | tr '[:lower:]' '[:upper:]')_LINKER=$clang"
    export "CC_${target_env}=$clang"
    export "AR_${target_env}=$bin/llvm-ar"
    target_args+=(--target "$target")
done

# One cargo run builds every target, sharing the host-side work.
cargo build --release --package bbd "${target_args[@]}" "$@"

mkdir -p out
# Gradle packages every out/bbd-*, so clear stale architecture builds first.
rm -f out/bbd-*
for target in "${TARGETS[@]}"; do
    daemon="out/bbd-$(arch_of "$target")"
    cp "target/$target/release/bbd" "$daemon"
    echo "built: $daemon ($(du -h "$daemon" | cut -f1))"
done

# ---- build the app, which carries the module --------------------------------
# The APK is also the flashable root module: Gradle packages module/ and
# the daemons in out/ into the root of the archive.
(cd android && ./gradlew --quiet :app:assembleRelease)
version="$(sed -n 's/^version=//p' module/module.prop)"
# The final .zip lets root managers select this file, and removing it
# gives the APK its installable name without changing the signed bytes.
zip="out/big_brother-$version.apk.zip"
cp android/app/build/outputs/apk/release/app-release.apk "$zip"
echo "built: $zip ($(du -h "$zip" | cut -f1))"

# R8's mapping for this build: what turns a stack trace from the app's log
# back into class and method names.
mapping="out/big_brother-$version-mapping.txt"
cp android/app/build/outputs/mapping/release/mapping.txt "$mapping"
echo "built: $mapping"
