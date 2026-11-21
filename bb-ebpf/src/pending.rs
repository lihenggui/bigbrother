// Copyright (C) 2026 Mercury Li
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

use aya_ebpf::{macros::map, maps::LruHashMap};

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) struct Pending {
    pub(super) path_ptr: u64,
    /// openat2 only: user pointer to `struct open_how`, else 0.
    pub(super) how_ptr: u64,
    pub(super) dfd: i32,
    pub(super) flags: u32,
    pub(super) abi: u32,
    pub(super) op: u32,
    /// Set by `file_open` once it has reported a file for this syscall.
    pub(super) reported: u32,
    /// Always 0. Fills what would be padding after `reported`, which no
    /// store would write (see `assert_no_padding`).
    pub(super) _pad: u32,
}

bb_common::assert_no_padding!(Pending {
    path_ptr,
    how_ptr,
    dfd,
    flags,
    abi,
    op,
    reported,
    _pad
});

/// Covers kernels without task storage and calls for which allocation failed.
/// LRU bounds the state orphaned by a thread dying mid-syscall.
#[map]
static PENDING: LruHashMap<u64, Pending> = LruHashMap::with_max_entries(8192, 0);

#[inline(always)]
pub(super) fn remember(id: u64, pending: Pending) -> Result<(), i32> {
    #[cfg(feature = "task-storage")]
    if let Some(slot) = local::reusable_slot(id) {
        // A newly allocated slot takes over from any previous hash entry.
        if unsafe { (*slot).id } == 0 {
            let _ = PENDING.remove(&id);
        }
        unsafe { slot.write(local::Call { id, pending }) };
        return Ok(());
    }
    PENDING.insert(&id, &pending, 0).map_err(|e| e as i32)
}

#[inline(always)]
pub(super) fn mark_reported(id: u64) -> bool {
    #[cfg(feature = "task-storage")]
    if let Some(slot) = local::active(id) {
        unsafe { (*slot).pending.reported = 1 };
        return true;
    }
    if let Some(pending) = PENDING.get_ptr_mut(&id) {
        unsafe { (*pending).reported = 1 };
        return true;
    }
    false
}

#[inline(always)]
pub(super) fn take(id: u64) -> Option<Pending> {
    #[cfg(feature = "task-storage")]
    if let Some(slot) = local::active(id) {
        let pending = unsafe { (*slot).pending };
        // Zero identifies newly allocated storage. No app has this pid_tgid.
        unsafe { (*slot).id = u64::MAX };
        return Some(pending);
    }
    let pending = unsafe { PENDING.get(&id) }.copied()?;
    let _ = PENDING.remove(&id);
    Some(pending)
}

#[cfg(feature = "task-storage")]
mod local {
    use super::Pending;
    use aya_ebpf::{
        bindings::{
            BPF_F_NO_PREALLOC, BPF_LOCAL_STORAGE_GET_F_CREATE,
            bpf_map_type::BPF_MAP_TYPE_TASK_STORAGE,
        },
        helpers::{bpf_get_current_task_btf, bpf_task_storage_get},
        macros::{btf_map, map},
        maps::PerCpuArray,
    };
    use core::ptr;

    #[repr(C)]
    pub(super) struct Call {
        // A non-leader exec can change tid while returning from the syscall.
        pub(super) id: u64,
        pub(super) pending: Pending,
    }

    bb_common::assert_no_padding!(Call { id, pending });

    // Task storage requires a BTF map definition. The pointer types describe
    // its fields to the loader and are never dereferenced.
    #[repr(C)]
    struct TaskMap {
        r#type: *const [i32; BPF_MAP_TYPE_TASK_STORAGE as usize],
        key: *const i32,
        value: *const Call,
        map_flags: *const [i32; BPF_F_NO_PREALLOC as usize],
    }
    // SAFETY: the descriptor is immutable and each task owns its value.
    unsafe impl Sync for TaskMap {}

    #[btf_map]
    static TASK_CALLS: TaskMap = TaskMap {
        r#type: ptr::null(),
        key: ptr::null(),
        value: ptr::null(),
        map_flags: ptr::null(),
    };

    // Seeing a thread twice on one CPU makes allocating reusable state worthwhile.
    // Collisions and migrations only postpone allocation. The hash still pairs the call.
    #[map]
    static RECENT: PerCpuArray<u64> = PerCpuArray::with_max_entries(64, 0);

    #[inline(always)]
    pub(super) fn reusable_slot(id: u64) -> Option<*mut Call> {
        if let Some(slot) = slot(0) {
            return Some(slot);
        }
        let recent = RECENT.get_ptr_mut((id as u32) & 63)?;
        if unsafe { recent.replace(id) } != id {
            return None;
        }
        slot(BPF_LOCAL_STORAGE_GET_F_CREATE.into())
    }

    #[inline(always)]
    fn slot(flags: u64) -> Option<*mut Call> {
        let task = unsafe { bpf_get_current_task_btf() };
        let slot = unsafe {
            bpf_task_storage_get(
                (&raw const TASK_CALLS).cast_mut().cast(),
                task,
                ptr::null_mut(),
                flags,
            )
        };
        (!slot.is_null()).then_some(slot.cast())
    }

    #[inline(always)]
    pub(super) fn active(id: u64) -> Option<*mut Call> {
        let slot = slot(0)?;
        (unsafe { (*slot).id } == id).then_some(slot)
    }
}
