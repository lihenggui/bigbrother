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

//! SQLite storage.
//!
//! Opens are aggregated rather than logged one row each: a row is one
//! (hour, uid, path, access mode) with success/failure counters. An app that
//! reads the same photo 500 times in an hour costs one row.

mod reader;
mod sandbox;
mod schema;
mod writer;

pub mod net;

pub use reader::{Reader, Stats};
pub use sandbox::{AppOpsUndo, KnownApp, KnownKind, SandboxEntry};
pub use schema::check_module_database;
pub use writer::{Counters, Writer};

pub const DEFAULT_DB: &str = "/data/adb/big_brother/bb.db";

#[cfg(test)]
mod tests;
