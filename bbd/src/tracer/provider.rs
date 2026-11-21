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

//! Ingest accesses reported by the provider hook.

use bb_common::FIRST_APPLICATION_UID;

use super::Tracer;
use crate::{
    packages::{app_id_of, user_of},
    paths, personal,
    record::{Category, MODE_QUERY, MODE_READ, MODE_VIA_PROVIDER, MODE_WRITE},
    spool::{self, ProviderEvent, Spool},
};

impl Tracer {
    /// Take in what the LSPosed hook recorded in the provider processes.
    pub(crate) fn ingest_provider_events(
        &mut self,
        collect: impl FnOnce(&mut Spool) -> Vec<ProviderEvent>,
    ) {
        if !self.ingest_provider_events {
            return;
        }
        for ev in collect(&mut self.spool) {
            self.counters.seen += 1;
            // Calls between system components are not app behaviour.
            if app_id_of(ev.uid) < FIRST_APPLICATION_UID {
                continue;
            }
            if let Some(collection) = personal::collection(&ev.uri) {
                let mode = match ev.op {
                    spool::Op::Query => MODE_QUERY,
                    spool::Op::Open if ev.write => MODE_WRITE | MODE_VIA_PROVIDER,
                    spool::Op::Open => MODE_READ | MODE_VIA_PROVIDER,
                };
                self.store(
                    ev.uid,
                    Category::Personal,
                    collection,
                    mode,
                    true,
                    ev.ts,
                    ev.items,
                );
                continue;
            }
            // The processes that hold personal data serve other things too
            // (dictionaries, blocked numbers, carrier settings); only the
            // storage providers are recorded in full.
            if !ev.storage_provider {
                continue;
            }
            let (cat, path, mode) = match ev.op {
                spool::Op::Query => (
                    Category::Provider,
                    spool::normalize_uri(&ev.uri),
                    MODE_QUERY,
                ),
                spool::Op::Open => {
                    let mut mode = MODE_VIA_PROVIDER;
                    if ev.read || !ev.write {
                        mode |= MODE_READ;
                    }
                    if ev.write {
                        mode |= MODE_WRITE;
                    }
                    // Prefer the real file; fall back to the URI when the
                    // provider served something that isn't on shared storage.
                    match paths::canonicalize(&ev.path, user_of(ev.uid)) {
                        Some(path) if paths::is_own_dir(&path, self.packages.for_uid(ev.uid)) => {
                            continue;
                        }
                        Some(path) => (Category::Storage, path, mode),
                        None => (Category::Provider, spool::normalize_uri(&ev.uri), mode),
                    }
                }
            };
            self.store(ev.uid, cat, path, mode, true, ev.ts, ev.items);
        }
    }
}
