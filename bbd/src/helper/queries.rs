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

//! Independent catalog and permission-usage request slots.

use std::{
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};

use anyhow::Context as _;
use tokio::{
    sync::{mpsc, oneshot},
    time::{sleep, timeout},
};

use super::{Bridge, Pipe};
use crate::{appops, sync::lock};

const APPOPS_GIVE_UP: u32 = 5;
const BOOT_WAIT: Duration = Duration::from_secs(30);
const INITIAL_WAIT: Duration = Duration::from_secs(3);
const MAX_REPLY_BYTES: usize = 8 * 1024 * 1024;

#[derive(Clone, Copy, Default)]
pub(super) struct Ready {
    pub(super) catalog: bool,
    pub(super) usage: bool,
    pub(super) generation: u64,
}

#[derive(Clone, Copy)]
pub(super) enum Domain {
    Catalog,
    Usage,
}

#[derive(Clone, Copy, PartialEq)]
enum Request {
    Catalog,
    Configure,
    Poll,
}

impl Request {
    fn domain(self) -> Domain {
        match self {
            Self::Catalog => Domain::Catalog,
            Self::Configure | Self::Poll => Domain::Usage,
        }
    }
}

struct Pending {
    id: u64,
    request: Request,
    buffer: String,
    oversized: bool,
    dirty: bool,
    replies: Vec<oneshot::Sender<anyhow::Result<String>>>,
}

#[derive(Default)]
pub(super) struct Queries {
    pub(super) input: Option<Pipe>,
    catalog: Option<Pending>,
    usage: Option<Pending>,
    configured: bool,
    next_id: u64,
    late_usage: Option<String>,
}

impl Queries {
    fn slot(&mut self, domain: Domain) -> &mut Option<Pending> {
        match domain {
            Domain::Catalog => &mut self.catalog,
            Domain::Usage => &mut self.usage,
        }
    }

    fn send(
        &mut self,
        request: Request,
        command: &str,
        dirty: &AtomicBool,
    ) -> anyhow::Result<oneshot::Receiver<anyhow::Result<String>>> {
        let (reply, receive) = oneshot::channel();
        if request == Request::Configure && self.configured {
            // The channel's receiver is still local.
            let _ = reply.send(Ok(String::new()));
            return Ok(receive);
        }
        anyhow::ensure!(
            request != Request::Poll || self.configured,
            "framework usage is not configured"
        );
        let domain = request.domain();
        if let Some(pending) = self.slot(domain) {
            anyhow::ensure!(
                pending.request == request,
                "framework query already in flight"
            );
            // A timed out waiter leaves the wire request in place until its terminal
            // frame, keeping a stuck query worker from accumulating more work.
            pending.replies.retain(|reply| !reply.is_closed());
            pending.replies.push(reply);
            return Ok(receive);
        }
        let id = self.next_id;
        self.next_id += 1;
        let prefix = match domain {
            Domain::Catalog => "catalog",
            Domain::Usage => "usage",
        };
        let input = self
            .input
            .as_ref()
            .context("framework helper is unavailable")?;
        let was_dirty = request == Request::Poll && dirty.swap(false, Ordering::AcqRel);
        if let Err(e) = input.send(&format!("{prefix} {id} {command}")) {
            if was_dirty {
                dirty.store(true, Ordering::Release);
            }
            return Err(e);
        }
        *self.slot(domain) = Some(Pending {
            id,
            request,
            buffer: String::new(),
            oversized: false,
            dirty: was_dirty,
            replies: vec![reply],
        });
        Ok(receive)
    }

    pub(super) fn receive(&mut self, domain: Domain, line: &str, dirty: &AtomicBool) {
        let Some((id, body)) = line.split_once(' ') else {
            return;
        };
        let slot = self.slot(domain);
        let Some(pending) = slot
            .as_mut()
            .filter(|pending| Some(pending.id) == id.parse().ok())
        else {
            return;
        };
        if let Some(row) = body.strip_prefix("row ") {
            if !pending.oversized && pending.buffer.len() + row.len() < MAX_REPLY_BYTES {
                pending.buffer.push_str(row);
                pending.buffer.push('\n');
            } else {
                pending.oversized = true;
            }
            return;
        }
        let success = match (pending.request, body) {
            (Request::Configure, "ok") | (Request::Catalog | Request::Poll, "end") => {
                !pending.oversized
            }
            (_, body) if body.starts_with("err ") => false,
            _ => return,
        };
        let pending = slot.take().expect("matched above");
        if success && pending.request == Request::Configure {
            self.configured = true;
        }
        if !success && pending.dirty {
            dirty.store(true, Ordering::Release);
        }
        let mut buffer = pending.buffer;
        let mut replies = pending
            .replies
            .into_iter()
            .filter(|reply| !reply.is_closed())
            .peekable();
        if replies.peek().is_none() && success && pending.request == Request::Poll {
            self.late_usage = Some(buffer);
            return;
        }
        while let Some(reply) = replies.next() {
            let result = if !success {
                Err(anyhow::anyhow!("framework query failed"))
            } else if replies.peek().is_some() {
                Ok(buffer.clone())
            } else {
                Ok(std::mem::take(&mut buffer))
            };
            // The caller can time out while the terminal frame is being read.
            let _ = reply.send(result);
        }
    }

    pub(super) fn reset(&mut self, dirty: &AtomicBool) {
        if self.usage.as_ref().is_some_and(|pending| pending.dirty) {
            dirty.store(true, Ordering::Release);
        }
        *self = Self {
            next_id: self.next_id,
            late_usage: self.late_usage.take(),
            ..Self::default()
        };
    }
}

impl Bridge {
    pub(crate) async fn load_catalog(
        &self,
        interfaces: impl Iterator<Item = &'static str>,
        wait: Duration,
    ) -> anyhow::Result<String> {
        let interfaces = interfaces.collect::<Vec<_>>().join(" ");
        timeout(wait, async {
            self.0
                .ready
                .subscribe()
                .wait_for(|ready| ready.catalog)
                .await?;
            self.request(Request::Catalog, &interfaces).await
        })
        .await?
    }

    pub(crate) async fn poll(&self, wait: Duration) -> anyhow::Result<Vec<appops::Entry>> {
        timeout(wait, async {
            if !lock(&self.0.queries).configured {
                anyhow::ensure!(
                    self.0.ready.borrow().usage,
                    "framework usage is unavailable"
                );
                let ops = appops::ops().collect::<Vec<_>>().join(",");
                self.request(Request::Configure, &format!("configure {ops}"))
                    .await?;
            }
            let reading = self.request(Request::Poll, "poll").await?;
            Ok(appops::parse(&reading))
        })
        .await?
    }

    pub(crate) fn take_late_usage(&self) -> Option<Vec<appops::Entry>> {
        let late = lock(&self.0.queries).late_usage.take()?;
        Some(appops::parse(&late))
    }

    async fn request(&self, request: Request, command: &str) -> anyhow::Result<String> {
        let reply = lock(&self.0.queries).send(request, command, &self.0.dirty)?;
        reply.await.context("the framework helper ended")?
    }
}

pub(super) async fn initial_usage(
    bridge: Bridge,
    readings: mpsc::Sender<Option<Vec<appops::Entry>>>,
) {
    let mut ready = bridge.0.ready.subscribe();
    let mut last_generation = None;
    // A service that never registers eventually reports unavailable, without
    // taking the catalog or a later connection down with it.
    let expired = timeout(
        BOOT_WAIT * APPOPS_GIVE_UP,
        ready.wait_for(|state| state.usage),
    )
    .await
    .is_err();
    if expired && readings.send(None).await.is_err() {
        return;
    }
    loop {
        let generation = match ready
            .wait_for(|state| state.usage && Some(state.generation) != last_generation)
            .await
        {
            Ok(state) => state.generation,
            Err(_) => return,
        };
        last_generation = Some(generation);
        if let Some(reading) = first_reading(&bridge, generation).await
            && readings.send(reading).await.is_err()
        {
            return;
        }
    }
}

async fn first_reading(bridge: &Bridge, generation: u64) -> Option<Option<Vec<appops::Entry>>> {
    for attempt in 0..APPOPS_GIVE_UP {
        if attempt != 0 {
            sleep(Duration::from_secs(1 << (attempt - 1))).await;
        }
        let ready = *bridge.0.ready.borrow();
        if !ready.usage || ready.generation != generation {
            return None;
        }
        if let Ok(reading) = bridge.poll(INITIAL_WAIT).await {
            return Some(Some(reading));
        }
    }
    Some(None)
}
