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

use anyhow::{Context as _, anyhow};
use aya_build::Toolchain;

/// Build both syscall-state backends for the target architecture.
fn main() -> anyhow::Result<()> {
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
    let out = std::path::PathBuf::from(std::env::var_os("OUT_DIR").context("missing OUT_DIR")?);
    std::fs::rename(out.join("bb-ebpf"), out.join("bb-ebpf-legacy"))?;
    build(&["task-storage"])
}
