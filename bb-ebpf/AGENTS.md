# AGENTS.md: eBPF programs

`no_std` programs built with aya-ebpf, nightly Rust and bpf-linker; `bbd/build.rs`
builds them through `aya-build`, and `./scripts/build.sh` builds everything.
The programs and `bb-common/` are GPL-2.0-or-later; the `license` section must
stay `GPL`, which the GPL-only helpers require.

## Constraints

- Target GKI kernels from 5.10 on arm64 and x86_64. Use only helpers and
  program types those kernels have. Struct offsets are not compiled in: the
  daemon reads them from BTF at startup (`bbd/src/btf.rs`) and passes them in.
- Only the `security_file_open` probe is required. Every other probe must be
  optional: the daemon attaches what the kernel allows and reports the rest
  as unavailable (`coverage_*` in `bbd stats`).
- The verifier allows 512 bytes of stack. Assemble large events in a per-CPU
  scratch map (as `file_open` does with `FILE_SCRATCH`), bound every loop, and
  read kernel and user memory only through the probe-read helpers.
- Event layouts are shared with the daemon through `bb-common`: `#[repr(C)]`,
  4-byte fields, no padding. Change both sides together.

## Cost on the hot path

These programs run on every file open and many system calls of every process.

- Check the uid first and return for processes that are not apps.
- Decide whether an event is wanted before writing it to the ring buffer;
  copy only kept events in.
- Submit with `wakeup_flags()`, which wakes the daemon only when the buffer is
  filling up. Never submit, output or discard with flag 0: that wakes the
  daemon for every event.

## Testing

The programs cannot be unit tested. Load them on a device or emulator: build
with `./scripts/build.sh`, install the package, and open the app, which
replaces the module with its own copy when the daemon differs. Then check that
records arrive (`scripts/check-canary.sh` covers every kind).
