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

//! Recover guards from the daemon's locks and condition variables.

use std::sync::{
    LockResult, Mutex, MutexGuard, PoisonError, RwLock, RwLockReadGuard, RwLockWriteGuard,
};

#[inline]
pub fn recover<T>(result: LockResult<T>) -> T {
    result.unwrap_or_else(PoisonError::into_inner)
}

#[inline]
pub fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    recover(mutex.lock())
}

#[inline]
pub fn read<T>(lock: &RwLock<T>) -> RwLockReadGuard<'_, T> {
    recover(lock.read())
}

#[inline]
pub fn write<T>(lock: &RwLock<T>) -> RwLockWriteGuard<'_, T> {
    recover(lock.write())
}
