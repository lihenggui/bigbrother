#!/usr/bin/env bash
# Cross-compile bbd (with the eBPF object embedded) for Android arm64, then
# build the APK, which is both the app and the flashable root module.
#
# Requires: rustup target aarch64-linux-android, nightly toolchain with
# rust-src, bpf-linker in PATH, an Android NDK, a JDK and the Android SDK.
set -euo pipefail

cd "$(dirname "$0")/.."

TARGET=aarch64-linux-android
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
[[ -x "$bin/${TARGET}${API}-clang" ]] || die "no NDK toolchain for this host at $bin"
command -v bpf-linker >/dev/null || die "bpf-linker not found; install it with: cargo install bpf-linker"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/${TARGET}${API}-clang"
export CC_aarch64_linux_android="$bin/${TARGET}${API}-clang"
export AR_aarch64_linux_android="$bin/llvm-ar"

cargo build --release --package bbd --target "$TARGET" "$@"

mkdir -p out
cp "target/$TARGET/release/bbd" out/bbd
echo "built: out/bbd ($(du -h out/bbd | cut -f1))"

# ---- build the app, which carries the module --------------------------------
# The APK is also the flashable root module: Gradle packages module/ and
# out/bbd into the root of the archive.
(cd android && ./gradlew --quiet :app:assembleRelease)
version="$(sed -n 's/^version=//p' module/module.prop)"
apk="out/big_brother-$version.apk"
cp android/app/build/outputs/apk/release/app-release.apk "$apk"
echo "built: $apk ($(du -h "$apk" | cut -f1))"

# The same file under the name root managers' file pickers accept. Flashing
# it installs the app as well (see module/customize.sh).
zip="${apk%.apk}.zip"
cp "$apk" "$zip"
echo "built: $zip (same file, for flashing)"
