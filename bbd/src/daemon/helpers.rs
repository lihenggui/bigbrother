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

//! Read Android service information without holding up the event loop.

use std::time::Duration;

use crate::{
    binder::{self, Catalog},
    helper::Bridge,
};

const CATALOG_RETRY: Duration = Duration::from_secs(30);
const CATALOG_ATTEMPTS: u32 = 20;
const CATALOG_TIMEOUT: Duration = Duration::from_secs(10);

/// Cache a successful catalog for the daemon's lifetime, even across helper restarts.
pub(super) fn spawn_catalog_loader(bridge: Bridge) -> tokio::task::JoinHandle<Option<Catalog>> {
    tokio::spawn(async move {
        for attempt in 1..=CATALOG_ATTEMPTS {
            let error = match binder::load(&bridge, CATALOG_TIMEOUT).await {
                Ok(catalog) => return Some(catalog),
                Err(e) => e,
            };
            if attempt == CATALOG_ATTEMPTS {
                eprintln!("bbd: warning: not recording service calls: {error:#}");
                break;
            }
            tokio::time::sleep(CATALOG_RETRY).await;
        }
        None
    })
}
