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

# Boot Android's kernels in QEMU and run `bbd selftest` on each: the eBPF
# programs must load, attach and fire on every kernel line the module
# supports, and only a real kernel can say whether they do.
#
# The kernels are those of the x86_64 emulator images (kernel-ranchu), the
# GKI kernels of each Android release built for the emulator. Each boots
# with no Android userspace: an initramfs with a static busybox and a
# static bbd mounts the kernel filesystems the daemon uses, runs the
# self-test and prints a result line on the serial console. This checks the
# x86_64 build of the eBPF programs and the daemon; the arm64 build runs on
# devices (scripts/check-canary.sh).
#
#   scripts/check-kernels.sh                      every kernel in KERNELS
#   scripts/check-kernels.sh --api 33 --api 34    some of them
#   scripts/check-kernels.sh --kernel PATH        a kernel image of your own
#
# Options:
#   --bbd PATH       the daemon to run, a static x86_64 build; built for
#                    x86_64-unknown-linux-musl when not given (build_bbd)
#   --timeout SECS   how long one boot may take (default 180)
#
# Requires: qemu-system-x86_64 ($QEMU), a static busybox ($BUSYBOX, else
# busybox in PATH) and python3; to build the daemon, the Rust target
# x86_64-unknown-linux-musl and musl-gcc ($MUSL_CC). Uses KVM when
# /dev/kvm is usable.
# Kernels are downloaded once into $BB_KERNEL_CACHE (default
# ~/.cache/big_brother/kernels).
#
# Exits non-zero if any kernel does not pass.
set -euo pipefail

cd "$(dirname "$0")/.."

# API level, system image (in https://dl.google.com/android/repository/
# sys-img/google_apis/, listed in sys-img2-3.xml there) and the SHA-256 of
# its kernel-ranchu. API 31 and 32 (android12-5.10) are not here: their
# kernels are built without BTF (no /sys/kernel/btf/vmlinux), which the
# daemon needs for struct offsets, and so cannot run it.
KERNELS=(
    "33 x86_64-33_r17.zip 2daeafcade4f0bd35bcbd4b7502a1bd3d576297833bffd32339b2a2df95fc2d9"
    "34 x86_64-34_r14.zip b1e5cd842220175a4c07be9a2bcf5beb7b336794b00cf9b7b53abf35557b24df"
    "35 x86_64-35_r09.zip 987818a35792fb62f6ea6aa604d5fa476e1e85c84fc1cbac3979e1ae26965f46"
    "36 x86_64-36_r07.zip 3f2c91f26b27e7c3a6f4a158383d7fb9a4cc87a2c321fc3ad77dc833b9e61a86"
    "36.1 x86_64-36.1_r04.zip 8e673c5d9b9f5e44f056346eda93adf8b96d3c9f300ce7b4fcc209d6ada906a5"
    "37 x86_64-37.0_r08.zip b044f78db74915d62f87d70824538e6061c923883c808af88b905af41cee12be"
)
IMAGES=https://dl.google.com/android/repository/sys-img/google_apis

CACHE="${BB_KERNEL_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/big_brother/kernels}"
QEMU="${QEMU:-qemu-system-x86_64}"
TIMEOUT=180
BBD=
APIS=()
OWN_KERNELS=()

die() {
    echo "error: $*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
    --api) APIS+=("$2"); shift 2 ;;
    --kernel) OWN_KERNELS+=("$2"); shift 2 ;;
    --bbd) BBD="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    -h | --help) sed -n '17,45p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1" ;;
    esac
done

command -v "$QEMU" >/dev/null || die "$QEMU not found; set QEMU"
BUSYBOX="${BUSYBOX:-$(command -v busybox || true)}"
[[ -x "$BUSYBOX" ]] || die "busybox not found; set BUSYBOX to a static busybox"
command -v python3 >/dev/null || die "python3 not found"

# Copy one file out of a remote zip, reading only its central directory
# and the file itself: an image is 1.5 to 2 GB, its kernel about 20 MB.
fetch_member() {
    local url="$1" member="$2" out="$3"
    python3 -I - "$url" "$member" "$out" <<'EOF'
import shutil, sys, time, urllib.error, urllib.request, zipfile

TIMEOUT = 60  # seconds per request
ATTEMPTS = 4  # with 2, 4 and 8 seconds between them

def request(req, check):
    """Make a request, trying again after a network error or a server
    error; returns what `check` makes of the response."""
    for attempt in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return check(r)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            client_error = isinstance(e, urllib.error.HTTPError) and e.code < 500
            if client_error or attempt == ATTEMPTS - 1:
                sys.exit(f"{req.full_url}: {e}")
            time.sleep(2 << attempt)

class Remote:
    """A seekable file over HTTP range requests."""
    def __init__(self, url):
        self.url, self.pos = url, 0
        head = urllib.request.Request(url, method="HEAD")
        self.size = request(head, lambda r: int(r.headers["Content-Length"]))
    def seekable(self): return True
    def tell(self): return self.pos
    def seek(self, offset, whence=0):
        self.pos = [offset, self.pos + offset, self.size + offset][whence]
        return self.pos
    def read(self, n=-1):
        end = self.size if n < 0 else min(self.size, self.pos + n)
        if end <= self.pos:
            return b""
        req = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{end - 1}"})

        def part(r):
            # Anything but the range asked for, such as the whole file
            # (200), is not what reading on would assume.
            if r.status != 206:
                sys.exit(f"{self.url}: HTTP {r.status} to a range request; need 206")
            data = r.read()
            if len(data) != end - self.pos:
                raise ConnectionError(f"{len(data)} of {end - self.pos} bytes")
            return data

        data = request(req, part)
        self.pos += len(data)
        return data

url, member, out = sys.argv[1:]
with zipfile.ZipFile(Remote(url)) as z:
    # The zip CRC of the member is checked as it is read.
    with z.open(member) as src, open(out + ".part", "wb") as dst:
        shutil.copyfileobj(src, dst, 1 << 22)
EOF
    mv "$out.part" "$out"
}

# The kernel of one image, downloaded once and checked against its hash.
kernel_of() {
    local zip="$1" sha="$2" out="$CACHE/${1%.zip}-kernel-ranchu"
    mkdir -p "$CACHE"
    if [[ ! -f "$out" ]]; then
        echo "downloading the kernel of $zip" >&2
        fetch_member "$IMAGES/$zip" x86_64/kernel-ranchu "$out"
    fi
    local got
    got="$(sha256sum "$out" | cut -d' ' -f1)"
    if [[ "$got" != "$sha" ]]; then
        rm -f "$out"
        die "kernel of $zip: SHA-256 $got, expected $sha"
    fi
    echo "$out"
}

# A static bbd for x86_64: there is no Android userspace, so no bionic to
# run the module's own build with. It is the same code and the same eBPF
# programs with musl as the C library. (Static bionic is no way round it:
# the NDK's libc.a carries a Rust standard library of its own.)
build_bbd() {
    local cc="${MUSL_CC:-$(command -v x86_64-linux-musl-gcc || command -v musl-gcc || true)}"
    [[ -n "$cc" ]] || die "musl-gcc not found (package musl-tools); set MUSL_CC or pass --bbd"
    # For SQLite, which is compiled from source.
    CC_x86_64_unknown_linux_musl="$cc" \
        cargo build --release --package bbd --target x86_64-unknown-linux-musl >&2
    echo target/x86_64-unknown-linux-musl/release/bbd
}

# What runs as /init: mount what the daemon reads, run the self-test, say
# how it ended and power off.
INIT='#!/bin/busybox sh
/bin/busybox --install -s /bin
mkdir -p /proc /sys /tmp
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t tmpfs tmpfs /tmp
mount -t bpf bpf /sys/fs/bpf
mount -t tracefs tracefs /sys/kernel/tracing
mount -t debugfs debugfs /sys/kernel/debug
# SELinux is built in and runs without a policy; its filesystem is how the
# daemon tells (syscalls::selinux_running), as on a device.
mount -t selinuxfs selinuxfs /sys/fs/selinux
if /bbd --db /tmp/bb.db selftest; then
    echo "BBD-SELFTEST PASS"
else
    echo "BBD-SELFTEST FAIL"
fi
poweroff -f
'

# The initramfs, as an uncompressed newc archive, which every kernel reads.
make_initramfs() {
    local out="$1" dir
    dir="$(mktemp -d)"
    # As on a device: every uid may look up paths from the root.
    chmod 755 "$dir"
    mkdir -p "$dir/bin"
    cp "$BUSYBOX" "$dir/bin/busybox"
    cp "$BBD" "$dir/bbd"
    printf '%s' "$INIT" >"$dir/init"
    chmod 755 "$dir/init" "$dir/bbd" "$dir/bin/busybox"
    (cd "$dir" && find . | "$BUSYBOX" cpio -o -H newc 2>/dev/null) >"$out"
    rm -rf "$dir"
}

# What the emulator passes to these kernels (kernel_cmdline.txt in the
# images that have one).
CMDLINE="8250.nr_uarts=1 clocksource=pit"

accel=tcg
if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    accel=kvm
fi

# Boot one kernel; prints its console to $log. Passes if the self-test did.
boot() {
    local kernel="$1" initramfs="$2" log="$3"
    local cpu=max
    [[ "$accel" == kvm ]] && cpu=host
    timeout --foreground "$TIMEOUT" "$QEMU" \
        -accel "$accel" -cpu "$cpu" -smp 2 -m 2048 \
        -kernel "$kernel" -initrd "$initramfs" \
        -append "console=ttyS0 $CMDLINE panic=-1 loglevel=4" \
        -nographic -no-reboot -nic none -monitor none -serial stdio \
        </dev/null >"$log" 2>&1 || true
    grep -q '^BBD-SELFTEST PASS' "$log"
}

[[ -n "$BBD" ]] || BBD="$(build_bbd)"
[[ -x "$BBD" ]] || die "$BBD: not an executable"

targets=()
for path in "${OWN_KERNELS[@]}"; do
    targets+=("$path|$path")
done
if [[ ${#OWN_KERNELS[@]} -eq 0 ]]; then
    for entry in "${KERNELS[@]}"; do
        read -r api zip sha <<<"$entry"
        if [[ ${#APIS[@]} -gt 0 ]] && [[ ! " ${APIS[*]} " == *" $api "* ]]; then
            continue
        fi
        targets+=("API $api|$(kernel_of "$zip" "$sha")")
    done
fi
[[ ${#targets[@]} -gt 0 ]] || die "no kernel selected"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
make_initramfs "$work/initramfs.cpio"

failed=0
summary=()
for target in "${targets[@]}"; do
    name="${target%%|*}"
    kernel="${target#*|}"
    log="$work/console.log"
    [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::group::$name"
    started=$SECONDS
    if boot "$kernel" "$work/initramfs.cpio" "$log"; then
        result=PASS
    else
        result=FAIL
        failed=1
    fi
    # The self-test's lines and how the boot ended, not the kernel's.
    grep -E '^(kernel |ok |FAIL |selftest |bbd: |Error|Caused by|    [0-9]+: |BBD-SELFTEST)' "$log" || tail -n 40 "$log"
    [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::endgroup::"
    release="$(sed -n 's/^kernel //p' "$log" | head -1)"
    summary+=("$result $name ${release:-(did not boot)} in $((SECONDS - started)) s")
done

printf '%s\n' "${summary[@]}"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf -- '- %s\n' "${summary[@]}" >>"$GITHUB_STEP_SUMMARY"
fi
exit "$failed"
