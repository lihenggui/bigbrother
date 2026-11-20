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

//! Stand-ins for `Rc`, `RefCell` and `Cell` with their API, for the fakes
//! the sandbox's tests hand it: its effects run on a thread of their own and
//! so must be `Send`.

use std::sync::{Mutex, RwLock, RwLockReadGuard, RwLockWriteGuard};

pub use std::sync::Arc as Rc;

#[derive(Default, Debug)]
pub struct RefCell<T>(RwLock<T>);

impl<T> RefCell<T> {
    pub fn new(value: T) -> Self {
        Self(RwLock::new(value))
    }

    pub fn borrow(&self) -> RwLockReadGuard<'_, T> {
        self.0.read().unwrap()
    }

    pub fn borrow_mut(&self) -> RwLockWriteGuard<'_, T> {
        self.0.write().unwrap()
    }
}

#[derive(Default, Debug)]
pub struct Cell<T>(Mutex<T>);

impl<T: Copy> Cell<T> {
    pub fn new(value: T) -> Self {
        Self(Mutex::new(value))
    }

    pub fn get(&self) -> T {
        *self.0.lock().unwrap()
    }

    pub fn set(&self, value: T) {
        *self.0.lock().unwrap() = value;
    }
}
