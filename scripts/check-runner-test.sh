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
cd "$(dirname "$0")/.."

# Stub the toolchains so the runner and hook can be tested without a build.
scratch=$(mktemp -d "$PWD/.check-runner.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
fixture="$scratch/repo"
mkdir -p "$scratch/bin" "$fixture"/{android,module,scripts,.githooks}
cp -p scripts/check.sh "$fixture/scripts/"
cp -p .githooks/pre-push "$fixture/.githooks/"
printf '# GNU General Public License\n' >"$fixture/module/service.sh"
cat >"$scratch/bin/cargo" <<'SH'
#!/usr/bin/env bash
printf 'cargo %s%s\n' "${RUSTFLAGS:+${RUSTFLAGS} }" "$*" >>"$CALLS"
if [[ ${FAIL_CARGO:-} == "$1" ]]; then exit 23; fi
SH
cat >"$fixture/scripts/build.sh" <<'SH'
#!/usr/bin/env bash
# GNU General Public License
printf 'build %s\n' "$CI" >>"$CALLS"
SH
cat >"$fixture/scripts/check-module-recovery.sh" <<'SH'
#!/bin/sh
# GNU General Public License
exit 0
SH
cat >"$fixture/android/gradlew" <<'SH'
#!/usr/bin/env bash
printf 'gradle %s %s\n' "$CI" "$*" >>"$CALLS"
SH
chmod +x "$scratch/bin/cargo" "$fixture/scripts/"*.sh "$fixture/android/gradlew"
git -C "$fixture" init -q
git -C "$fixture" add .
export PATH="$scratch/bin:$PATH" CALLS="$scratch/calls"
export CI=false
unset RUSTFLAGS
runner="$fixture/scripts/check.sh"

"$runner" >"$scratch/output"
diff -u - "$CALLS" <<'EXPECTED'
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
cargo --cfg bpf_target_arch="x86_64" +nightly clippy -Z build-std=core --target bpfel-unknown-none -p bb-ebpf --features  -- -A clippy::all -D clippy::too_many_lines
cargo --cfg bpf_target_arch="x86_64" +nightly clippy -Z build-std=core --target bpfel-unknown-none -p bb-ebpf --features task-storage -- -A clippy::all -D clippy::too_many_lines
cargo test
gradle true spotlessCheck :build-logic:convention:spotlessCheck
build true
gradle true test
gradle true :app:dependencyGuard :app:checkReleaseBadging :app:licenseeAndroidRelease :helper:licenseeAndroidRelease
gradle true :app:lintRelease :helper:lintRelease
gradle true lintStructure -PlintStructureOnly
EXPECTED
mv "$CALLS" "$scratch/full"

"$runner" --fast >"$scratch/output"
diff -u - "$CALLS" <<'EXPECTED'
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings
cargo test
gradle true spotlessCheck :build-logic:convention:spotlessCheck
gradle true lintStructure -PlintStructureOnly
EXPECTED
mv "$CALLS" "$scratch/fast"

"$fixture/.githooks/pre-push" origin example.invalid </dev/null >"$scratch/output"
diff -u "$scratch/fast" "$CALLS"

: >"$CALLS"
status=0
FAIL_CARGO=+nightly "$runner" >"$scratch/output" 2>&1 || status=$?
[[ "$status" == 23 ]]
grep -Fx 'Failed: Check eBPF function length (exit 23)' "$scratch/output"
diff -u <(head -n 3 "$scratch/full") "$CALLS"

: >"$CALLS"
printf '# Missing the license\n' >"$fixture/module/service.sh"
status=0
"$fixture/.githooks/pre-push" origin example.invalid </dev/null >"$scratch/output" 2>&1 || status=$?
[[ "$status" == 1 ]]
grep -Fx 'Missing license header:' "$scratch/output"
grep -Fx 'module/service.sh' "$scratch/output"
grep -Fx 'Failed: Check license headers (exit 1)' "$scratch/output"
diff -u <(head -n 3 "$scratch/fast") "$CALLS"

: >"$CALLS"
status=0
"$runner" --unknown >"$scratch/output" 2>&1 || status=$?
[[ "$status" == 2 && ! -s "$CALLS" ]]
grep -F 'Usage:' "$scratch/output"

printf 'Host check runner tests passed.\n'
