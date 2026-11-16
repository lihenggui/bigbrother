use which::which;

/// Rebuild when the `bpf-linker` binary changes; the crate depends on it
/// without cargo knowing (same approach as the upstream aya template).
fn main() {
    let bpf_linker = which("bpf-linker").expect("bpf-linker not found in PATH");
    println!("cargo:rerun-if-changed={}", bpf_linker.to_str().unwrap());
}
