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

//! Record network attempts, traffic, and refused name lookups.

use std::collections::HashMap;

use bb_common::{ConnectEvent, EventHeader, TrafficEvent, read_event};

use super::Tracer;
use crate::{
    clock::now,
    net::{self, Destination},
    record::{Category, MODE_CONNECT},
    store::net::{NetKey, NetSeen},
};

/// The most destinations an app has rows of its own for in an hour.
pub(super) const NET_DESTINATIONS_PER_HOUR: usize = 512;

impl Tracer {
    #[inline]
    pub(super) fn on_traffic(&mut self, bytes: &[u8], uid: u32, ts: i64) {
        let Some(ev) = read_event::<TrafficEvent>(bytes) else {
            return;
        };
        self.counters.seen += 1;
        // Only what went to the internet, as the connections are.
        let Some(address) = net::public(ev.family, ev.port, &ev.addr) else {
            return;
        };
        let wide = |low: u32, high: u32| (u64::from(high) << 32) | u64::from(low);
        let host = self.host_of(uid, address.ip());
        let key = self.net_key(uid, ts, host, address.ip().to_string(), address.port());
        self.writer.net_record(
            key,
            NetSeen {
                sent: wide(ev.sent_low, ev.sent_high),
                recv: wide(ev.recv_low, ev.recv_high),
                counted: 1,
                ..NetSeen::default()
            },
            ts,
        );
    }

    #[inline]
    pub(super) fn on_connect(&mut self, bytes: &[u8], hdr: EventHeader, ts: i64) {
        let Some(ev) = read_event::<ConnectEvent>(bytes) else {
            return;
        };
        self.counters.seen += 1;
        if let Some(address) = net::public(ev.family, ev.port, &ev.addr) {
            let (uid, _) = self.owner_uid(hdr.uid, hdr.pid);
            let refused = self
                .net
                .as_ref()
                .is_some_and(|net| net.refuses(uid, address));
            let host = self.host_of(uid, address.ip());
            let key = self.net_key(uid, ts, host, address.ip().to_string(), address.port());
            self.writer.net_record(
                key,
                NetSeen {
                    conn: u32::from(!refused),
                    refused: u32::from(refused),
                    ..NetSeen::default()
                },
                ts,
            );
            return;
        }
        let Some(destination) = Destination::new(ev.family, ev.port, &ev.addr) else {
            return;
        };
        // A port of the app's own is the app talking to itself. The
        // socket tables are compared by the uid the kernel sees.
        if destination.is_loopback() && self.listeners.owned_by(destination.port(), hdr.uid) {
            self.explain("connect");
            return;
        }
        let (uid, origin) = self.owner_uid(hdr.uid, hdr.pid);
        // The outcome is not known (see the kernel side); what is
        // recorded is that the app tried.
        let Some(subject) = self.scans.subject(uid, &destination, ts) else {
            return;
        };
        self.store(
            uid,
            Category::Network,
            subject,
            MODE_CONNECT | origin,
            true,
            ts,
            0,
        );
    }

    /// What a row of the network records is about. An app has so many
    /// destinations of its own in an hour; what it connects to past them
    /// is counted together, of no destination: an app that goes through
    /// addresses, or looks up names made up one after another, would
    /// otherwise fill the database, which every record shares.
    pub(super) fn net_key(
        &mut self,
        uid: u32,
        ts: i64,
        host: String,
        addr: String,
        port: u16,
    ) -> NetKey {
        let hour = ts.div_euclid(3600);
        if self.net_listed.0 != hour {
            self.net_listed = (hour, HashMap::new());
        }
        let listed = self.net_listed.1.entry(uid).or_default();
        let destination = (host, addr, port);
        if listed.len() >= NET_DESTINATIONS_PER_HOUR && !listed.contains(&destination) {
            return NetKey {
                uid,
                hour,
                host: String::new(),
                addr: String::new(),
                port: 0,
            };
        }
        listed.insert(destination.clone());
        let (host, addr, port) = destination;
        NetKey {
            uid,
            hour,
            host,
            addr,
            port,
        }
    }

    /// The name `uid` was given `address` for; empty if none is known.
    fn host_of(&self, uid: u32, address: std::net::IpAddr) -> String {
        self.net
            .as_ref()
            .and_then(|net| net.names.as_ref())
            .and_then(|names| names.host_of(uid, address))
            .unwrap_or_default()
    }

    /// Record one batch of the refused lookups before returning to the loop.
    pub(crate) fn take_refused_lookups(&mut self) {
        let Some(refused) = self.refused_lookups.take() else {
            return;
        };
        let ts = now();
        for (uid, host) in refused.batch() {
            let key = self.net_key(uid, ts, host, String::new(), 0);
            self.writer.net_record(
                key,
                NetSeen {
                    refused: 1,
                    ..NetSeen::default()
                },
                ts,
            );
        }
        self.counters.dns_lookups_dropped = refused.dropped();
        self.refused_lookups = Some(refused);
    }
}
