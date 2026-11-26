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

set -euo pipefail

fast=false
if [[ $# -eq 1 && $1 == --fast ]]; then
    fast=true
elif [[ $# -ne 0 ]]; then
    printf 'Usage: %s [--fast]\n' "$0" >&2
    exit 2
fi

cd "$(dirname "$0")/.."

# Gradle stages the same two Robolectric SDKs as CI and uses them offline.
export CI=true

step_name=
trap 'status=$?; if (( status != 0 )); then printf "Failed: %s (exit %d)\n" "$step_name" "$status" >&2; fi' EXIT

step() {
    step_name=$1
    shift
    printf '\n==> %s\n' "$step_name"
    "$@"
}

check_ebpf() {
    local arch features
    for arch in x86_64 aarch64; do
        for features in "" task-storage; do
            printf 'Checking eBPF for %s (%s)\n' "$arch" "${features:-legacy}"
            RUSTFLAGS="--cfg bpf_target_arch=\"$arch\"" \
                cargo +nightly clippy -Z build-std=core --target bpfel-unknown-none \
                    -p bb-ebpf --features "$features" -- -D warnings -D clippy::too_many_lines
        done
    done
}

check_shell_syntax() {
    local script
    for script in module/*.sh; do
        sh -n "$script"
    done
    for script in scripts/*.sh .githooks/pre-push; do
        bash -n "$script"
    done
    if command -v shellcheck >/dev/null; then
        shellcheck -s sh module/*.sh
        shellcheck scripts/*.sh .githooks/pre-push
    fi
}

check_license_headers() {
    local missing status
    if missing=$(git grep -LF 'GNU General Public License' -- '*.rs' '*.sh' '*.py' .githooks/pre-push); then
        printf 'Missing license header:\n%s\n' "$missing" >&2
        return 1
    else
        status=$?
        # git grep returns 1 if every file has a header.
        if (( status != 1 )); then
            return "$status"
        fi
    fi
}

gradle() (
    cd android && ./gradlew "$@"
)

step 'Test CI gates' python3 .github/tests/test_ci_gates.py
step 'Check shell syntax and lint' check_shell_syntax
step 'Test the module scripts' ./scripts/check-module-recovery.sh
step 'Check license headers' check_license_headers
if command -v cargo-deny >/dev/null; then
    step 'Check Rust dependency licenses' cargo deny check licenses
fi
step 'Check formatting' cargo fmt --all --check
step 'Lint' cargo clippy --all-targets -- -D warnings
if ! "$fast"; then
    step 'Lint eBPF' check_ebpf
fi
step 'Test the daemon' cargo test
step "Check the app's formatting and license headers" gradle spotlessCheck :build-logic:convention:spotlessCheck

if ! "$fast"; then
    # The app tests and release checks need the daemons packaged from out/.
    step 'Build the APK' ./scripts/build.sh
    step 'Test the app' gradle test
    step "Check the app's dependencies, permissions and licenses" \
        gradle :app:dependencyGuard :app:checkReleaseBadging :app:licenseeAndroidRelease :helper:licenseeAndroidRelease
    step 'Lint the app and its helper' gradle :app:lintRelease :helper:lintRelease
fi

step 'Check Kotlin source structure and test names' gradle lintStructure -PlintStructureOnly
