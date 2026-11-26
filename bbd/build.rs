// Copyright (C) 2026 Mercury Li
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License version 3 as
// published by the Free Software Foundation.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.
//
// Based on aya-template: https://github.com/aya-rs/aya-template

use std::{env, fs, path::PathBuf, process::Command};

use anyhow::{Context as _, anyhow, ensure};
use aya_build::Toolchain;

fn main() -> anyhow::Result<()> {
    let module_prop = "../module/module.prop";
    println!("cargo:rerun-if-changed={module_prop}");
    let properties = fs::read_to_string(module_prop).context("reading module version")?;
    let version = properties
        .lines()
        .find_map(|line| line.strip_prefix("version="))
        .filter(|version| !version.is_empty())
        .context("missing version in module.prop")?;
    println!(
        "cargo:rustc-env=BB_VERSION={}",
        version.strip_prefix('v').unwrap_or(version)
    );
    build_probes()
}

/// Build both syscall-state backends for the target architecture.
fn build_probes() -> anyhow::Result<()> {
    let cargo_metadata::Metadata { packages, .. } = cargo_metadata::MetadataCommand::new()
        .no_deps()
        .exec()
        .context("MetadataCommand::exec")?;
    let ebpf_package = packages
        .into_iter()
        .find(|cargo_metadata::Package { name, .. }| name.as_str() == "bb-ebpf")
        .ok_or_else(|| anyhow!("bb-ebpf package not found"))?;
    let cargo_metadata::Package {
        name,
        manifest_path,
        ..
    } = ebpf_package;
    let root_dir = manifest_path
        .parent()
        .context("missing eBPF crate directory")?
        .as_str();
    let build = |features| {
        let package = aya_build::Package {
            name: name.as_str(),
            root_dir,
            features,
            ..Default::default()
        };
        aya_build::build_ebpf([package], Toolchain::default())
    };
    build(&[])?;
    let out = PathBuf::from(env::var_os("OUT_DIR").context("missing OUT_DIR")?);
    fs::rename(out.join("bb-ebpf"), out.join("bb-ebpf-legacy"))?;
    build(&["task-storage"])?;
    let objcopy = find_objcopy()?;
    for name in ["bb-ebpf", "bb-ebpf-legacy"] {
        // Aya needs BTF, BTF.ext and the surviving sections' relocations.
        let status = Command::new(&objcopy)
            .arg("--strip-debug")
            .arg(out.join(name))
            .status()
            .with_context(|| format!("running {}", objcopy.display()))?;
        ensure!(
            status.success(),
            "stripping debug sections from {name}: {status}"
        );
    }
    Ok(())
}

fn find_objcopy() -> anyhow::Result<PathBuf> {
    if let Ok(path) = which::which("llvm-objcopy").or_else(|_| which::which("rust-objcopy")) {
        return Ok(path);
    }
    // The rustup llvm-tools component keeps its tools beside the host libs.
    let rustc = env::var_os("RUSTC").context("missing RUSTC")?;
    let output = Command::new(rustc)
        .args(["--print", "target-libdir", "--target", &env::var("HOST")?])
        .output()
        .context("locating the Rust host libraries")?;
    ensure!(
        output.status.success(),
        "locating the Rust host libraries: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let mut path = PathBuf::from(String::from_utf8(output.stdout)?.trim());
    path.pop();
    path.push("bin");
    path.push(format!("llvm-objcopy{}", env::consts::EXE_SUFFIX));
    ensure!(
        path.is_file(),
        "llvm-objcopy not found; install it or run rustup component add llvm-tools"
    );
    Ok(path)
}
