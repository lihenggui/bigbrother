//! Minimal BTF reader: finds byte offsets of struct fields in the running
//! kernel.
//!
//! The eBPF programs read kernel structs (`struct file`, `struct dentry`...)
//! whose layout differs between kernel versions and builds. Instead of
//! compiling offsets in, we look them up in the type information the kernel
//! publishes at `/sys/kernel/btf/vmlinux` and pass them to the programs at
//! load time. (This is what libbpf's CO-RE does automatically for C programs;
//! Rust eBPF can't emit CO-RE relocations yet.)
//!
//! Format reference: https://docs.kernel.org/bpf/btf.html

use anyhow::{Context as _, anyhow, bail};
use bb_common::{
    OFF_COUNT, OFF_DENTRY_INODE, OFF_DENTRY_NAME, OFF_DENTRY_PARENT, OFF_DENTRY_SB,
    OFF_FILE_DENTRY, OFF_FILE_FLAGS, OFF_INODE_MODE, OFF_SB_DEV, OFF_SB_MAGIC,
};

pub const VMLINUX_BTF: &str = "/sys/kernel/btf/vmlinux";

const BTF_MAGIC: u16 = 0xeb9f;

const KIND_INT: u32 = 1;
const KIND_ARRAY: u32 = 3;
const KIND_STRUCT: u32 = 4;
const KIND_UNION: u32 = 5;
const KIND_ENUM: u32 = 6;
const KIND_TYPEDEF: u32 = 8;
const KIND_VOLATILE: u32 = 9;
const KIND_CONST: u32 = 10;
const KIND_RESTRICT: u32 = 11;
const KIND_FUNC_PROTO: u32 = 13;
const KIND_VAR: u32 = 14;
const KIND_DATASEC: u32 = 15;
const KIND_DECL_TAG: u32 = 17;
const KIND_TYPE_TAG: u32 = 18;
const KIND_ENUM64: u32 = 19;

struct Type {
    kind: u32,
    name_off: u32,
    /// Referenced type id (pointers, typedefs, qualifiers).
    ref_type: u32,
    kind_flag: bool,
    /// Range into `Btf::members` (structs and unions only).
    members: std::ops::Range<usize>,
}

struct Member {
    name_off: u32,
    type_id: u32,
    bit_offset: u32,
}

pub struct Btf {
    /// Indexed by type id; id 0 is `void`.
    types: Vec<Type>,
    members: Vec<Member>,
    strings: Vec<u8>,
}

fn u32_at(data: &[u8], offset: usize) -> anyhow::Result<u32> {
    let bytes = data
        .get(offset..offset + 4)
        .ok_or_else(|| anyhow!("BTF truncated at byte {offset}"))?;
    Ok(u32::from_le_bytes(bytes.try_into().unwrap()))
}

impl Btf {
    pub fn from_kernel() -> anyhow::Result<Self> {
        let data = std::fs::read(VMLINUX_BTF).with_context(|| {
            format!("reading {VMLINUX_BTF} (kernel built without CONFIG_DEBUG_INFO_BTF?)")
        })?;
        Self::parse(&data)
    }

    pub fn parse(data: &[u8]) -> anyhow::Result<Self> {
        if data.len() < 24 || u16::from_le_bytes([data[0], data[1]]) != BTF_MAGIC {
            bail!("not little-endian BTF data");
        }
        let hdr_len = u32_at(data, 4)? as usize;
        let type_off = hdr_len + u32_at(data, 8)? as usize;
        let type_len = u32_at(data, 12)? as usize;
        let str_off = hdr_len + u32_at(data, 16)? as usize;
        let str_len = u32_at(data, 20)? as usize;
        let strings = data
            .get(str_off..str_off + str_len)
            .ok_or_else(|| anyhow!("BTF string section out of bounds"))?
            .to_vec();
        let section = data
            .get(type_off..type_off + type_len)
            .ok_or_else(|| anyhow!("BTF type section out of bounds"))?;

        let mut types = vec![Type {
            kind: 0,
            name_off: 0,
            ref_type: 0,
            kind_flag: false,
            members: 0..0,
        }];
        let mut members = Vec::new();
        let mut pos = 0;
        while pos < section.len() {
            let name_off = u32_at(section, pos)?;
            let info = u32_at(section, pos + 4)?;
            let size_or_type = u32_at(section, pos + 8)?;
            pos += 12;
            let vlen = (info & 0xffff) as usize;
            let kind = (info >> 24) & 0x1f;
            let kind_flag = info >> 31 != 0;

            let mut range = 0..0;
            // Bytes of kind-specific data following the common header.
            let extra = match kind {
                KIND_INT | KIND_VAR | KIND_DECL_TAG => 4,
                KIND_ARRAY => 12,
                KIND_STRUCT | KIND_UNION => {
                    let start = members.len();
                    for i in 0..vlen {
                        let at = pos + i * 12;
                        members.push(Member {
                            name_off: u32_at(section, at)?,
                            type_id: u32_at(section, at + 4)?,
                            bit_offset: u32_at(section, at + 8)?,
                        });
                    }
                    range = start..members.len();
                    vlen * 12
                }
                KIND_ENUM | KIND_FUNC_PROTO => vlen * 8,
                KIND_DATASEC | KIND_ENUM64 => vlen * 12,
                _ => 0,
            };
            pos += extra;
            types.push(Type {
                kind,
                name_off,
                ref_type: size_or_type,
                kind_flag,
                members: range,
            });
        }
        Ok(Self {
            types,
            members,
            strings,
        })
    }

    fn name(&self, offset: u32) -> &[u8] {
        let rest = self.strings.get(offset as usize..).unwrap_or(&[]);
        let end = rest.iter().position(|&b| b == 0).unwrap_or(rest.len());
        &rest[..end]
    }

    /// Follow typedefs and qualifiers to the underlying type.
    fn resolve(&self, mut id: u32) -> Option<&Type> {
        for _ in 0..32 {
            let ty = self.types.get(id as usize)?;
            match ty.kind {
                KIND_TYPEDEF | KIND_VOLATILE | KIND_CONST | KIND_RESTRICT | KIND_TYPE_TAG => {
                    id = ty.ref_type;
                }
                _ => return Some(ty),
            }
        }
        None
    }

    /// Bit offset of `field` within `ty`, searching anonymous struct/union
    /// members too (the kernel uses them heavily). Also returns the field's
    /// type id.
    fn find_member(&self, ty: &Type, field: &str) -> Option<(u32, u32)> {
        for m in &self.members[ty.members.clone()] {
            // With kind_flag set, the top 8 bits hold a bitfield size.
            let bit_offset = if ty.kind_flag {
                m.bit_offset & 0x00ff_ffff
            } else {
                m.bit_offset
            };
            if m.name_off != 0 {
                if self.name(m.name_off) == field.as_bytes() {
                    return Some((bit_offset, m.type_id));
                }
            } else if let Some(inner) = self.resolve(m.type_id)
                && matches!(inner.kind, KIND_STRUCT | KIND_UNION)
                && let Some((inner_off, type_id)) = self.find_member(inner, field)
            {
                return Some((bit_offset + inner_off, type_id));
            }
        }
        None
    }

    /// Byte offset of `path` (e.g. `["f_path", "dentry"]`) inside the named
    /// struct.
    pub fn offset_of(&self, struct_name: &str, path: &[&str]) -> anyhow::Result<u32> {
        let describe = || format!("struct {struct_name} field {}", path.join("."));
        // Forward declarations share the name but have no members.
        let mut ty = self
            .types
            .iter()
            .find(|t| {
                t.kind == KIND_STRUCT
                    && !t.members.is_empty()
                    && self.name(t.name_off) == struct_name.as_bytes()
            })
            .ok_or_else(|| anyhow!("struct {struct_name} not found in kernel BTF"))?;
        let mut bits = 0;
        for (i, field) in path.iter().enumerate() {
            let (bit_offset, type_id) = self
                .find_member(ty, field)
                .ok_or_else(|| anyhow!("{} not found in kernel BTF", describe()))?;
            bits += bit_offset;
            if i + 1 < path.len() {
                ty = self
                    .resolve(type_id)
                    .filter(|t| matches!(t.kind, KIND_STRUCT | KIND_UNION))
                    .ok_or_else(|| anyhow!("{}: {field} is not a struct", describe()))?;
            }
        }
        if bits % 8 != 0 {
            bail!("{} is a bitfield", describe());
        }
        Ok(bits / 8)
    }

    /// The offsets the eBPF programs need, in `bb_common::OFF_*` order.
    pub fn kernel_offsets(&self) -> anyhow::Result<[u32; OFF_COUNT]> {
        let mut offsets = [0; OFF_COUNT];
        for (slot, name, path) in [
            (OFF_FILE_DENTRY, "file", &["f_path", "dentry"][..]),
            (OFF_FILE_FLAGS, "file", &["f_flags"]),
            (OFF_DENTRY_PARENT, "dentry", &["d_parent"]),
            (OFF_DENTRY_NAME, "dentry", &["d_name", "name"]),
            (OFF_DENTRY_SB, "dentry", &["d_sb"]),
            (OFF_DENTRY_INODE, "dentry", &["d_inode"]),
            (OFF_INODE_MODE, "inode", &["i_mode"]),
            (OFF_SB_MAGIC, "super_block", &["s_magic"]),
            (OFF_SB_DEV, "super_block", &["s_dev"]),
        ] {
            offsets[slot] = self.offset_of(name, path)?;
        }
        Ok(offsets)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Runs against the BTF of whatever kernel the tests execute on.
    #[test]
    fn resolves_offsets_from_the_running_kernel() {
        let Ok(btf) = Btf::from_kernel() else {
            eprintln!("skipped: no {VMLINUX_BTF} on this machine");
            return;
        };
        let off = btf.kernel_offsets().unwrap();

        // `struct qstr { u64 hash_len; const char *name; }` puts name 8 bytes
        // into d_name, so d_name.name must be exactly d_name + 8.
        assert_eq!(
            off[OFF_DENTRY_NAME],
            btf.offset_of("dentry", &["d_name"]).unwrap() + 8
        );
        // `struct path { struct vfsmount *mnt; struct dentry *dentry; }`.
        assert_eq!(
            off[OFF_FILE_DENTRY],
            btf.offset_of("file", &["f_path"]).unwrap() + 8
        );
        // Pointer fields are pointer-aligned.
        for slot in [
            OFF_FILE_DENTRY,
            OFF_DENTRY_PARENT,
            OFF_DENTRY_NAME,
            OFF_DENTRY_SB,
            OFF_DENTRY_INODE,
        ] {
            assert_eq!(off[slot] % 8, 0, "slot {slot}");
        }
        // All fields are distinct within each struct.
        assert_ne!(off[OFF_DENTRY_PARENT], off[OFF_DENTRY_SB]);
        assert_ne!(off[OFF_DENTRY_SB], off[OFF_DENTRY_INODE]);
        assert_ne!(off[OFF_SB_MAGIC], off[OFF_SB_DEV]);
        // i_mode is the first field of struct inode on every kernel so far.
        assert_eq!(off[OFF_INODE_MODE], 0);

        assert!(btf.offset_of("file", &["no_such_field"]).is_err());
        assert!(btf.offset_of("no_such_struct", &["x"]).is_err());
    }

    #[test]
    fn rejects_garbage() {
        assert!(Btf::parse(b"not btf at all, definitely not").is_err());
        assert!(Btf::parse(&[]).is_err());
    }
}
