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

//! The network of a sandboxed app, as the app shows it: what a destination
//! the user names is, and the answer of `bbd net activity`.
//!
//! Names and addresses here are those of other apps' connections: they are
//! data, compared and printed, never interpreted, and never logged.

use std::{
    collections::{BTreeMap, BTreeSet},
    net::{IpAddr, SocketAddr},
    path::Path,
};

use anyhow::Context as _;
use serde_json::{Value, json};

use crate::{
    packages::PER_USER_RANGE,
    sandbox::{items::Settings, items_of},
    store::{
        Reader,
        net::{NetRow, NetRule, NetSeen},
    },
};

/// A destination an app can be kept from.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Target {
    /// A name and every name under it.
    Domain(String),
    /// One name.
    Host(String),
    /// An address: one port of it, or every port.
    Ip(IpAddr, Option<u16>),
}

impl Target {
    /// Read a target as [`Self::to_text`] writes it: `domain:example.com`,
    /// `host:t.example.com`, `ip:203.0.113.7`, `ip:203.0.113.7:8080`,
    /// `ip:2001:db8::5`, `ip:[2001:db8::5]:443`.
    pub fn parse(text: &str) -> Option<Self> {
        let (kind, rest) = text.split_once(':')?;
        match kind {
            "domain" => name(rest).map(Target::Domain),
            "host" => name(rest).map(Target::Host),
            "ip" => {
                if let Ok(ip) = rest.parse::<IpAddr>() {
                    return Some(Target::Ip(ip.to_canonical(), None));
                }
                let socket = rest.parse::<SocketAddr>().ok()?;
                (socket.port() != 0)
                    .then(|| Target::Ip(socket.ip().to_canonical(), Some(socket.port())))
            }
            _ => None,
        }
    }

    pub fn to_text(&self) -> String {
        match self {
            Target::Domain(name) => format!("domain:{name}"),
            Target::Host(name) => format!("host:{name}"),
            Target::Ip(ip, None) => format!("ip:{ip}"),
            Target::Ip(ip, Some(port)) => format!("ip:{}", SocketAddr::new(*ip, *port)),
        }
    }

    /// Whether looking `host` up is refused by this target.
    pub fn covers_name(&self, host: &str) -> bool {
        match self {
            Target::Domain(name) => {
                host == name
                    || host
                        .strip_suffix(name.as_str())
                        .is_some_and(|rest| rest.ends_with('.'))
            }
            Target::Host(name) => host == name,
            Target::Ip(..) => false,
        }
    }
}

/// A host name as it is compared: lower case, without the dot that may end
/// it. `None` for what is not a name of at least two labels.
pub fn name(text: &str) -> Option<String> {
    let text = text.strip_suffix('.').unwrap_or(text).to_ascii_lowercase();
    let labels_ok = text.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    });
    // An address written out is not a name.
    (text.len() <= 253 && text.contains('.') && labels_ok && text.parse::<IpAddr>().is_err())
        .then_some(text)
}

/// Second-level names under which sites are registered, as far as the
/// common ones go: `example.co.uk` is a site, `co.uk` is not. A name under
/// one that is missing here is grouped one level too high, with the other
/// sites of that suffix; nothing but the grouping in the list depends on
/// it.
const SECOND_LEVEL: &[&str] = &[
    "ac", "co", "com", "edu", "gov", "mil", "net", "ne", "or", "org",
];

/// The site a host belongs to: the name it was registered under, for
/// grouping the hosts of one owner.
pub fn site_of(host: &str) -> &str {
    let mut labels = host.rsplit('.');
    let (Some(top), Some(second)) = (labels.next(), labels.next()) else {
        return host;
    };
    let mut keep = top.len() + 1 + second.len();
    // A two-letter top level with a generic second level: one more label.
    if top.len() == 2
        && SECOND_LEVEL.contains(&second)
        && let Some(third) = labels.next()
    {
        keep += 1 + third.len();
    }
    &host[host.len() - keep..]
}

/// What was seen of one thing over a period.
#[derive(Clone, Copy, Default)]
struct Sum {
    seen: NetSeen,
    first: i64,
    last: i64,
}

impl Sum {
    fn add(&mut self, row: &NetRow) {
        self.seen.conn += row.seen.conn;
        self.seen.refused += row.seen.refused;
        self.seen.sent += row.seen.sent;
        self.seen.recv += row.seen.recv;
        self.seen.counted += row.seen.counted;
        self.first = if self.first == 0 {
            row.first_ts
        } else {
            self.first.min(row.first_ts)
        };
        self.last = self.last.max(row.last_ts);
    }

    /// The counts as JSON; the bytes only where a connection was counted.
    fn fields(&self) -> serde_json::Map<String, Value> {
        let mut map = serde_json::Map::new();
        map.insert("conn".into(), self.seen.conn.into());
        map.insert("refused".into(), self.seen.refused.into());
        if self.seen.counted > 0 {
            map.insert("sent".into(), self.seen.sent.into());
            map.insert("recv".into(), self.seen.recv.into());
        }
        map
    }

    fn bytes(&self) -> u64 {
        self.seen.sent + self.seen.recv
    }
}

/// A destination of the list: a site with its hosts, or an address no name
/// is known of.
#[derive(Default)]
struct Destination {
    total: Sum,
    hosts: BTreeMap<String, Sum>,
    addrs: BTreeSet<String>,
    ports: BTreeMap<u16, Sum>,
    /// By the start of the hour.
    hours: BTreeMap<i64, Sum>,
}

/// The most addresses listed for one site.
const ADDRESSES_LISTED: usize = 16;

/// The rule that keeps the app from `name`, if one does.
fn rule_of_name<'a>(rules: &'a [(Target, &NetRule)], name: &str) -> Option<&'a str> {
    rules
        .iter()
        .find(|(target, _)| target.covers_name(name))
        .map(|(_, rule)| rule.target.as_str())
}

fn rule_of_addr<'a>(
    rules: &'a [(Target, &NetRule)],
    addr: &str,
    port: Option<u16>,
) -> Option<&'a str> {
    let ip = addr.parse::<IpAddr>().ok()?;
    rules
        .iter()
        .find(|(target, _)| *target == Target::Ip(ip, port))
        .map(|(_, rule)| rule.target.as_str())
}

/// What `bbd net activity` is asked about.
struct Query<'a> {
    uid: u32,
    chart: &'a Chart,
    rows: &'a [NetRow],
    rules: &'a [NetRule],
    /// Since when the connections are recorded, if they are.
    recording: Option<i64>,
    /// Whether the app is kept from the network altogether.
    blocked: bool,
    /// Whether byte counts and names can be had on this device.
    traffic: bool,
    names: bool,
}

impl Query<'_> {
    fn grouped(&self) -> (BTreeMap<String, Destination>, BTreeMap<String, Destination>) {
        let mut sites: BTreeMap<String, Destination> = BTreeMap::new();
        let mut addrs: BTreeMap<String, Destination> = BTreeMap::new();
        for row in self.rows {
            // What was seen past the most destinations listed of an hour:
            // counted in the chart, and of no destination.
            if row.host.is_empty() && row.addr.is_empty() {
                continue;
            }
            let destination: &mut Destination = if row.host.is_empty() {
                addrs.entry(row.addr.clone()).or_default()
            } else {
                let site: &mut Destination =
                    sites.entry(site_of(&row.host).to_string()).or_default();
                site.hosts.entry(row.host.clone()).or_default().add(row);
                site
            };
            destination.total.add(row);
            // A lookup that was refused went to no address.
            if !row.addr.is_empty() {
                destination.addrs.insert(row.addr.clone());
                destination.ports.entry(row.port).or_default().add(row);
            }
            destination
                .hours
                .entry(row.hour * HOUR)
                .or_default()
                .add(row);
        }
        (sites, addrs)
    }

    fn parsed_rules(&self) -> Vec<(Target, &NetRule)> {
        self.rules
            .iter()
            .filter_map(|rule| Some((Target::parse(&rule.target)?, rule)))
            .collect()
    }

    fn site_json(
        &self,
        site: &str,
        destination: &Destination,
        rules: &[(Target, &NetRule)],
    ) -> serde_json::Map<String, Value> {
        let mut map = destination.total.fields();
        map.insert("site".into(), site.into());
        // The whole site: a rule on the domain, not on one of its hosts.
        let whole = rules
            .iter()
            .find(|(target, _)| matches!(target, Target::Domain(name) if name == site))
            .map(|(_, rule)| rule.target.as_str());
        map.insert("rule".into(), whole.into());
        let hosts: Vec<Value> = destination
            .hosts
            .iter()
            .map(|(name, sum)| {
                let mut host = sum.fields();
                host.insert("name".into(), name.as_str().into());
                host.insert("rule".into(), rule_of_name(rules, name).into());
                Value::Object(host)
            })
            .collect();
        map.insert("hosts".into(), hosts.into());
        let listed: Vec<&str> = destination
            .addrs
            .iter()
            .take(ADDRESSES_LISTED)
            .map(String::as_str)
            .collect();
        map.insert("addrs".into(), listed.into());
        map.insert("addr_count".into(), destination.addrs.len().into());
        map.insert("ports".into(), ports_json(destination, None, rules));
        map.insert("first".into(), destination.total.first.into());
        map.insert("last".into(), destination.total.last.into());
        map
    }

    fn addr_json(
        &self,
        addr: &str,
        destination: &Destination,
        rules: &[(Target, &NetRule)],
    ) -> serde_json::Map<String, Value> {
        let mut map = destination.total.fields();
        map.insert("addr".into(), addr.into());
        map.insert("rule".into(), rule_of_addr(rules, addr, None).into());
        map.insert("ports".into(), ports_json(destination, Some(addr), rules));
        map.insert("first".into(), destination.total.first.into());
        map.insert("last".into(), destination.total.last.into());
        map
    }

    /// The answer of `bbd net activity`.
    fn activity(&self) -> Value {
        let rules = self.parsed_rules();
        let (sites, addrs) = self.grouped();
        // Most data first, then most connections: the order of the list
        // until the app sorts it otherwise.
        let mut destinations: Vec<(u64, u32, Value)> = sites
            .iter()
            .map(|(site, d)| (d, Value::Object(self.site_json(site, d, &rules))))
            .chain(
                addrs
                    .iter()
                    .map(|(addr, d)| (d, Value::Object(self.addr_json(addr, d, &rules)))),
            )
            .map(|(d, json)| (d.total.bytes(), d.total.seen.conn, json))
            .collect();
        destinations.sort_by_key(|d| std::cmp::Reverse((d.0, d.1)));

        let buckets = self.chart.buckets(
            self.rows
                .iter()
                .map(|row| (row.hour * HOUR, row.seen.conn, row.seen.refused)),
        );
        json!({
            "uid": self.uid,
            "since": self.chart.since,
            "until": self.chart.until,
            "bucket": self.chart.bucket,
            "recording": {"on": self.recording.is_some(), "since": self.recording},
            "network": if self.blocked { "blocked" } else { "allowed" },
            "traffic": self.traffic,
            "names": self.names,
            "buckets": buckets,
            "destinations": destinations.into_iter().map(|d| d.2).collect::<Vec<_>>(),
            "rules": self
                .rules
                .iter()
                .map(|rule| json!({
                    "target": rule.target, "created": rule.created, "manual": rule.manual,
                }))
                .collect::<Vec<_>>(),
        })
    }

    /// The answer of `bbd net destination`: one destination of the list,
    /// with the chart of what was seen of it. `None` if nothing was.
    fn destination(&self, target: &Target) -> Option<Value> {
        let rules = self.parsed_rules();
        let (sites, addrs) = self.grouped();
        let (mut map, destination) = match target {
            Target::Domain(site) => {
                let destination = sites.get(site)?;
                (self.site_json(site, destination, &rules), destination)
            }
            Target::Ip(ip, None) => {
                let addr = ip.to_string();
                let destination = addrs.get(&addr)?;
                (self.addr_json(&addr, destination, &rules), destination)
            }
            _ => return None,
        };
        let buckets = self.chart.buckets(
            destination
                .hours
                .iter()
                .map(|(t, sum)| (*t, sum.seen.conn, sum.seen.refused)),
        );
        map.insert("bucket".into(), self.chart.bucket.into());
        map.insert("buckets".into(), buckets);
        Some(Value::Object(map))
    }
}

const HOUR: i64 = 3600;
const DAY: i64 = 24 * HOUR;
const MOST_BUCKETS: usize = 1000;
const LONGEST_DAY: i64 = 2 * DAY;

/// Time range and grouping shared by both network charts.
#[derive(clap::Args)]
pub struct ChartOptions {
    /// Start of the period, in seconds since the epoch.
    #[arg(long)]
    since: i64,
    /// End of the period, in seconds since the epoch. Defaults to now.
    #[arg(long)]
    until: Option<i64>,
    /// What a bar groups by: hour or day.
    #[arg(long, default_value = "hour")]
    bucket: String,
    /// Fixed offset in seconds from UTC for days when day starts are omitted.
    #[arg(long, default_value_t = 0, allow_negative_numbers = true)]
    offset: i64,
    /// Starts of the local days in the period, in seconds since the epoch.
    #[arg(
        long,
        value_delimiter = ',',
        requires = "until",
        conflicts_with = "offset"
    )]
    day_starts: Vec<i64>,
}

impl ChartOptions {
    fn into_chart(self) -> anyhow::Result<Chart> {
        let bucket = match self.bucket.as_str() {
            "hour" => HOUR,
            "day" => DAY,
            other => anyhow::bail!("{other}: not hour or day"),
        };
        let since = self.since;
        let until = self.until.unwrap_or_else(crate::now);
        anyhow::ensure!(since < until, "the period is empty");
        // Leave room to align range endpoints to days or hours.
        let timestamps = (i64::MIN + LONGEST_DAY)..=(i64::MAX - LONGEST_DAY);
        anyhow::ensure!(
            timestamps.contains(&since) && timestamps.contains(&until),
            "network timestamps are out of range"
        );
        let starts = if self.day_starts.is_empty() {
            anyhow::ensure!(
                (-DAY + 1..DAY).contains(&self.offset),
                "{}: not an offset from UTC",
                self.offset
            );
            let offset = if bucket == DAY { self.offset } else { 0 };
            let start = since - (since + offset).rem_euclid(bucket);
            (start..until)
                .step_by(bucket as usize)
                .take(MOST_BUCKETS + 1)
                .collect()
        } else {
            anyhow::ensure!(bucket == DAY, "day starts require --bucket day");
            let starts = self.day_starts;
            let first = starts[0];
            let last = starts[starts.len() - 1];
            anyhow::ensure!(
                (since - LONGEST_DAY..=since).contains(&first)
                    && starts.get(1).is_none_or(|&next| since < next)
                    && last < until
                    && until <= last.saturating_add(LONGEST_DAY)
                    && starts.windows(2).all(|pair| {
                        pair[0] < pair[1] && pair[1] <= pair[0].saturating_add(LONGEST_DAY)
                    }),
                "day starts must be ordered and cover the period"
            );
            starts
        };
        anyhow::ensure!(
            starts.len() <= MOST_BUCKETS,
            "the period has more than {MOST_BUCKETS} buckets"
        );
        Ok(Chart {
            since,
            until,
            bucket,
            starts,
        })
    }
}

struct Chart {
    since: i64,
    until: i64,
    /// The grouping marker on the wire. A local day can vary in length.
    bucket: i64,
    starts: Vec<i64>,
}

impl Chart {
    /// A stored UTC hour goes with the day it started in. Where midnight
    /// falls inside an hour, the next day's query must leave that hour out.
    fn rows_since(&self) -> i64 {
        let first = self.starts[0];
        let first_hour = first + (HOUR - first.rem_euclid(HOUR)) % HOUR;
        self.since.max(first_hour)
    }

    /// Connections made and refused in each bar, including empty bars.
    fn buckets(&self, hours: impl Iterator<Item = (i64, u32, u32)>) -> Value {
        let mut counts = vec![(0, 0); self.starts.len()];
        for (at, conn, refused) in hours {
            let end = self.starts.partition_point(|&start| start <= at);
            if let Some(index) = end.checked_sub(1) {
                counts[index].0 += conn;
                counts[index].1 += refused;
            }
        }
        self.starts
            .iter()
            .zip(counts)
            .map(|(t, (conn, refused))| json!({"t": t, "conn": conn, "refused": refused}))
            .collect::<Vec<_>>()
            .into()
    }
}

/// The uid, the rows and the rules a query is about, read from the
/// database: the app is known by the entry it has or had in the sandbox.
struct Read {
    uid: u32,
    rows: Vec<NetRow>,
    rules: Vec<NetRule>,
    recording: Option<i64>,
    blocked: bool,
    traffic: bool,
    names: bool,
}

fn read(db: &Path, package: &str, user: u32, chart: &Chart) -> anyhow::Result<Read> {
    let reader = Reader::open(db)?;
    // An app installed again has an entry for each uid it had: the one
    // in the sandbox now, or else the one put in last.
    let entry = reader
        .sandbox_entries()?
        .into_iter()
        .filter(|entry| entry.package == package && entry.uid / PER_USER_RANGE == user)
        .max_by_key(|entry| (entry.enabled, entry.created))
        .with_context(|| format!("{package} was never in the sandbox"))?;
    let global = Settings::parse(&reader.sandbox_global()?);
    let meta = reader.stats()?.meta;
    let can = |key: &str| meta.get(key).copied().unwrap_or(0) > 0;
    Ok(Read {
        uid: entry.uid,
        rows: reader.net_rows(entry.uid, chart.rows_since(), chart.until)?,
        rules: reader.net_rules(entry.uid)?,
        recording: reader.net_recording()?.get(&entry.uid).copied(),
        blocked: entry.enabled && items_of(&entry, &global).network_refused(),
        traffic: can("coverage_net_traffic"),
        names: can("coverage_net_names"),
    })
}

impl Read {
    fn query<'a>(&'a self, chart: &'a Chart) -> Query<'a> {
        Query {
            uid: self.uid,
            chart,
            rows: &self.rows,
            rules: &self.rules,
            recording: self.recording,
            blocked: self.blocked,
            traffic: self.traffic,
            names: self.names,
        }
    }
}

fn print(mut answer: Value) -> anyhow::Result<()> {
    if let Value::Object(map) = &mut answer {
        map.insert("ok".into(), true.into());
    }
    println!("{answer}");
    Ok(())
}

/// `bbd net activity`.
pub fn activity(db: &Path, package: &str, user: u32, options: ChartOptions) -> anyhow::Result<()> {
    let chart = options.into_chart()?;
    let read = read(db, package, user, &chart)?;
    print(read.query(&chart).activity())
}

/// `bbd net destination`.
pub fn destination(
    db: &Path,
    package: &str,
    user: u32,
    target: &str,
    options: ChartOptions,
) -> anyhow::Result<()> {
    let target = Target::parse(target).context("not a domain or an address")?;
    let chart = options.into_chart()?;
    let read = read(db, package, user, &chart)?;
    let found = read
        .query(&chart)
        .destination(&target)
        .context("nothing is recorded of this destination")?;
    print(found)
}

fn ports_json(
    destination: &Destination,
    addr: Option<&str>,
    rules: &[(Target, &NetRule)],
) -> Value {
    destination
        .ports
        .iter()
        .map(|(port, sum)| {
            let mut map = sum.fields();
            map.insert("port".into(), (*port).into());
            if let Some(addr) = addr {
                map.insert("rule".into(), rule_of_addr(rules, addr, Some(*port)).into());
            }
            Value::Object(map)
        })
        .collect::<Vec<_>>()
        .into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{Counters, SandboxEntry, Writer, net::NetKey};

    fn chart_options(since: i64, until: i64, bucket: &str) -> ChartOptions {
        ChartOptions {
            since,
            until: Some(until),
            bucket: bucket.into(),
            offset: 0,
            day_starts: Vec::new(),
        }
    }

    #[test]
    fn targets_are_read_as_they_are_written() {
        for text in [
            "domain:example.com",
            "host:t.example.com",
            "ip:203.0.113.7",
            "ip:203.0.113.7:8080",
            "ip:2001:db8::5",
            "ip:[2001:db8::5]:443",
        ] {
            assert_eq!(Target::parse(text).unwrap().to_text(), text, "{text}");
        }
        // Names are compared in lower case, without a closing dot.
        assert_eq!(
            Target::parse("domain:Example.COM.").unwrap().to_text(),
            "domain:example.com"
        );
        // An IPv4 address in an IPv6 one is that IPv4 address.
        assert_eq!(
            Target::parse("ip:::ffff:203.0.113.7").unwrap().to_text(),
            "ip:203.0.113.7"
        );
        for text in [
            "",
            "example.com",
            "domain:",
            "domain:localhost",
            "domain:exa mple.com",
            "domain:203.0.113.7",
            "domain:example..com",
            "host:example.com/path",
            "ip:example.com",
            "ip:203.0.113.7:0",
            "ip:203.0.113.7:99999",
            "site:example.com",
        ] {
            assert_eq!(Target::parse(text), None, "{text}");
        }
    }

    #[test]
    fn a_domain_covers_the_names_under_it_and_a_host_only_itself() {
        let domain = Target::parse("domain:example.com").unwrap();
        assert!(domain.covers_name("example.com"));
        assert!(domain.covers_name("a.b.example.com"));
        assert!(!domain.covers_name("notexample.com"));
        assert!(!domain.covers_name("example.com.evil.test"));
        let host = Target::parse("host:t.example.com").unwrap();
        assert!(host.covers_name("t.example.com"));
        assert!(!host.covers_name("x.t.example.com"));
        assert!(!host.covers_name("example.com"));
        assert!(
            !Target::parse("ip:203.0.113.7")
                .unwrap()
                .covers_name("203.0.113.7")
        );
    }

    #[test]
    fn hosts_are_grouped_by_the_name_they_were_registered_under() {
        for (host, site) in [
            ("example.com", "example.com"),
            ("api.example.com", "example.com"),
            ("a.b.c.example.org", "example.org"),
            ("shop.example.co.uk", "example.co.uk"),
            ("www.example.com.cn", "example.com.cn"),
            ("example.cn", "example.cn"),
            ("cdn.example.io", "example.io"),
            // A generic second level under a long top level is a site.
            ("api.com.example", "com.example"),
        ] {
            assert_eq!(site_of(host), site, "{host}");
        }
    }

    fn row(hour: i64, host: &str, addr: &str, port: u16, seen: NetSeen) -> NetRow {
        NetRow {
            hour,
            host: host.into(),
            addr: addr.into(),
            port,
            seen,
            first_ts: hour * 3600 + 10,
            last_ts: hour * 3600 + 20,
        }
    }

    fn seen(conn: u32, refused: u32, bytes: Option<(u64, u64)>) -> NetSeen {
        NetSeen {
            conn,
            refused,
            sent: bytes.map_or(0, |b| b.0),
            recv: bytes.map_or(0, |b| b.1),
            counted: u32::from(bytes.is_some()),
        }
    }

    fn rule(target: &str) -> NetRule {
        NetRule {
            uid: 10234,
            target: target.into(),
            created: 5,
            manual: false,
        }
    }

    #[test]
    fn the_activity_groups_sites_and_addresses_and_names_the_rules() {
        let rows = [
            row(
                100,
                "api.example.com",
                "203.0.113.10",
                443,
                seen(3, 0, Some((100, 2000))),
            ),
            row(
                101,
                "api.example.com",
                "203.0.113.11",
                443,
                seen(2, 0, Some((50, 500))),
            ),
            row(
                101,
                "img.example.com",
                "203.0.113.10",
                443,
                seen(1, 0, None),
            ),
            row(
                100,
                "t.adnet.example",
                "203.0.113.50",
                443,
                seen(0, 7, None),
            ),
            row(101, "", "198.51.100.53", 8443, seen(4, 0, Some((10, 20)))),
            row(101, "", "198.51.100.53", 53, seen(0, 2, None)),
        ];
        let rules = [
            rule("domain:adnet.example"),
            rule("host:img.example.com"),
            rule("ip:198.51.100.53:53"),
        ];
        let chart = chart_options(100 * 3600, 102 * 3600, "hour")
            .into_chart()
            .unwrap();
        let query = Query {
            uid: 10234,
            chart: &chart,
            rows: &rows,
            rules: &rules,
            recording: Some(42),
            blocked: false,
            traffic: true,
            names: true,
        };
        let activity = query.activity();
        assert_eq!(activity["recording"], json!({"on": true, "since": 42}));
        assert_eq!(activity["network"], "allowed");
        assert_eq!(
            activity["buckets"],
            json!([
                {"t": 360_000, "conn": 3, "refused": 7},
                {"t": 363_600, "conn": 7, "refused": 2},
            ])
        );
        let destinations = activity["destinations"].as_array().unwrap();
        // Most data first.
        assert_eq!(destinations[0]["site"], "example.com");
        assert_eq!(destinations[0]["conn"], 6);
        assert_eq!(destinations[0]["sent"], 150);
        assert_eq!(destinations[0]["recv"], 2500);
        assert_eq!(destinations[0]["rule"], Value::Null);
        assert_eq!(
            destinations[0]["addrs"],
            json!(["203.0.113.10", "203.0.113.11"])
        );
        assert_eq!(destinations[0]["first"], 360_010);
        assert_eq!(destinations[0]["last"], 363_620);
        let hosts = destinations[0]["hosts"].as_array().unwrap();
        assert_eq!(hosts[0]["name"], "api.example.com");
        assert_eq!(hosts[0]["rule"], Value::Null);
        // A host no connection was counted of has no bytes, and its own
        // rule.
        assert_eq!(hosts[1]["name"], "img.example.com");
        assert_eq!(hosts[1].get("sent"), None);
        assert_eq!(hosts[1]["rule"], "host:img.example.com");

        assert_eq!(destinations[1]["addr"], "198.51.100.53");
        assert_eq!(destinations[1]["rule"], Value::Null);
        assert_eq!(
            destinations[1]["ports"],
            json!([
                {"port": 53, "conn": 0, "refused": 2, "rule": "ip:198.51.100.53:53"},
                {"port": 8443, "conn": 4, "refused": 0, "sent": 10, "recv": 20, "rule": null},
            ])
        );

        // The site a rule is on, and the host under it.
        assert_eq!(destinations[2]["site"], "adnet.example");
        assert_eq!(destinations[2]["rule"], "domain:adnet.example");
        assert_eq!(destinations[2]["hosts"][0]["rule"], "domain:adnet.example");
        assert_eq!(destinations[2]["refused"], 7);
        assert_eq!(activity["rules"].as_array().unwrap().len(), 3);

        let one = query
            .destination(&Target::parse("domain:example.com").unwrap())
            .unwrap();
        assert_eq!(one["site"], "example.com");
        assert_eq!(
            one["buckets"],
            json!([
                {"t": 360_000, "conn": 3, "refused": 0},
                {"t": 363_600, "conn": 3, "refused": 0},
            ])
        );
        let address = query
            .destination(&Target::parse("ip:198.51.100.53").unwrap())
            .unwrap();
        assert_eq!(address["buckets"][1]["refused"], 2);
        assert!(
            query
                .destination(&Target::parse("domain:absent.example").unwrap())
                .is_none()
        );
    }

    #[test]
    fn days_begin_where_the_device_has_them() {
        // Hours 39 and 40 of UTC: either side of midnight eight hours ahead.
        let rows = [
            row(39, "", "203.0.113.1", 80, seen(1, 0, None)),
            row(40, "", "203.0.113.1", 80, seen(2, 0, None)),
        ];
        let chart = ChartOptions {
            offset: 8 * 3600,
            ..chart_options(16 * 3600, 64 * 3600, "day")
        }
        .into_chart()
        .unwrap();
        let query = Query {
            uid: 1,
            chart: &chart,
            rows: &rows,
            rules: &[],
            recording: None,
            blocked: false,
            traffic: false,
            names: false,
        };
        assert_eq!(
            query.activity()["buckets"],
            json!([
                {"t": 16 * 3600, "conn": 1, "refused": 0},
                {"t": 40 * 3600, "conn": 2, "refused": 0},
            ])
        );
        // The chart of one destination has the same bars.
        let one = query
            .destination(&Target::parse("ip:203.0.113.1").unwrap())
            .unwrap();
        assert_eq!(one["buckets"], query.activity()["buckets"]);
        assert_eq!(one["bucket"], 86_400);
    }

    #[test]
    fn days_are_summed_and_empty_buckets_are_listed() {
        let rows = [row(48, "", "203.0.113.1", 80, seen(2, 0, None))];
        let chart = chart_options(DAY, 3 * DAY, "day").into_chart().unwrap();
        let query = Query {
            uid: 1,
            chart: &chart,
            rows: &rows,
            rules: &[],
            recording: None,
            blocked: true,
            traffic: false,
            names: false,
        };
        let activity = query.activity();
        assert_eq!(
            activity["buckets"],
            json!([
                {"t": 86_400, "conn": 0, "refused": 0},
                {"t": 172_800, "conn": 2, "refused": 0},
            ])
        );
        assert_eq!(activity["recording"], json!({"on": false, "since": null}));
        assert_eq!(activity["network"], "blocked");
    }

    #[test]
    fn days_follow_midnight_through_clock_changes() {
        // Midnight at the start of March 8 and 9, then November 1 and 2,
        // 2026, in Los Angeles. The days are one hour shorter or longer.
        for (day, next) in [
            (1_772_956_800, 1_773_039_600),
            (1_793_516_400, 1_793_606_400),
        ] {
            let previous = day - 86_400;
            let rows = [
                row(day / 3600 - 1, "", "203.0.113.1", 80, seen(1, 0, None)),
                row(day / 3600, "", "203.0.113.1", 80, seen(2, 1, None)),
                row(next / 3600 - 1, "", "203.0.113.1", 80, seen(4, 2, None)),
                row(next / 3600, "", "203.0.113.1", 80, seen(8, 0, None)),
            ];
            let chart = ChartOptions {
                day_starts: vec![previous, day, next],
                ..chart_options(day - 5400, next + 1800, "day")
            }
            .into_chart()
            .unwrap();
            let query = Query {
                uid: 1,
                chart: &chart,
                rows: &rows,
                rules: &[],
                recording: None,
                blocked: false,
                traffic: false,
                names: false,
            };
            let expected = json!([
                {"t": previous, "conn": 1, "refused": 0},
                {"t": day, "conn": 6, "refused": 3},
                {"t": next, "conn": 8, "refused": 0},
            ]);
            assert_eq!(query.activity()["buckets"], expected);
            let one = query
                .destination(&Target::parse("ip:203.0.113.1").unwrap())
                .unwrap();
            assert_eq!(one["buckets"], expected);
        }
    }

    #[test]
    fn a_utc_hour_straddling_midnight_is_read_only_for_the_day_it_started_in() {
        let dir = std::env::temp_dir().join(format!("bbd-net-midnight-{}", std::process::id()));
        let db = dir.join("bb.db");
        let midnight = DAY + 18 * HOUR + 15 * 60;
        let first = midnight - DAY;
        let since = midnight - 2 * HOUR;
        let until = midnight + 2 * HOUR;
        let mut writer = Writer::open(&db).unwrap();
        writer
            .put_sandbox(&SandboxEntry {
                uid: 10234,
                package: "com.example.network".into(),
                enabled: true,
                created: since,
                install_id: "test".into(),
                android_id: String::new(),
                saved_ops: String::new(),
                saved_permissions: String::new(),
                settings: String::new(),
            })
            .unwrap();
        for (at, connections) in [
            (midnight - 3600, 1),
            (midnight + 60, 2),
            (midnight + 3600, 4),
        ] {
            writer.net_record(
                NetKey {
                    uid: 10234,
                    hour: at / HOUR,
                    host: String::new(),
                    addr: "203.0.113.1".into(),
                    port: 80,
                },
                seen(connections, 0, None),
                at,
            );
        }
        writer.flush(&Counters::default(), until).unwrap();

        let activity = |since, until, day_starts| {
            let chart = ChartOptions {
                day_starts,
                ..chart_options(since, until, "day")
            }
            .into_chart()
            .unwrap();
            let read = read(&db, "com.example.network", 0, &chart).unwrap();
            read.query(&chart).activity()
        };
        let whole = activity(since, until, vec![first, midnight]);
        for (index, start, end, day, connections) in [
            (0, since, midnight, first, 3),
            (1, midnight, until, midnight, 4),
        ] {
            assert_eq!(whole["buckets"][index]["conn"], connections);
            let selected = activity(start, end, vec![day]);
            assert_eq!(selected["buckets"][0], whole["buckets"][index]);
            assert_eq!(selected["destinations"][0]["conn"], connections);
        }
        drop(writer);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn day_starts_must_be_ordered_cover_the_period_and_be_used_with_days() {
        let since = DAY + HOUR;
        let until = 4 * DAY;
        for (bucket, starts) in [
            ("hour", vec![DAY, 2 * DAY, 3 * DAY]),
            ("day", vec![DAY, 3 * DAY, 2 * DAY]),
            ("day", vec![DAY, 2 * DAY, 4 * DAY]),
            ("day", vec![2 * DAY, 3 * DAY]),
            ("day", vec![DAY]),
        ] {
            let options = ChartOptions {
                day_starts: starts,
                ..chart_options(since, until, bucket)
            };
            assert!(options.into_chart().is_err());
        }
    }
}
