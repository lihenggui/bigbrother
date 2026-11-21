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

//! Names and addresses used by destination rules.

use std::net::{IpAddr, SocketAddr};

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

#[cfg(test)]
mod tests {
    use super::*;

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
}
