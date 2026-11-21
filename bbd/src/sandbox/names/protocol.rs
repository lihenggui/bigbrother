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

//! Decode netd requests and address replies for the name relay.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use super::super::target;

/// What a request asks netd for.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Asked {
    /// The addresses of a name (`getaddrinfo`).
    Addresses(String),
    /// A name, in another way than its addresses being listed
    /// (`gethostbyname`, or a DNS query the app wrote itself).
    Name(String),
    /// A lookup of something that is not read here as a name: what netd
    /// would make of it is not known.
    Unreadable,
    /// Anything else: not a lookup of a name, or of one no rule can be
    /// about (an address written out, a name of one label).
    Other,
}

/// What a word given as the name to look up is: a name, something no rule
/// can be about, or neither.
fn named(word: Option<&str>, asked: impl Fn(String) -> Asked) -> Asked {
    let Some(word) = word else {
        return Asked::Unreadable;
    };
    if let Some(name) = target::name(word) {
        return asked(name);
    }
    // No name given (`^`), an address, or one label: rules name at least
    // two.
    let plain = !word.is_empty()
        && word
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
    if word == "^" || word.parse::<IpAddr>().is_ok() || plain {
        Asked::Other
    } else {
        Asked::Unreadable
    }
}

impl Asked {
    /// Read a request: words separated by spaces, up to a zero byte.
    pub(super) fn parse(request: &[u8]) -> Self {
        let end = request
            .iter()
            .position(|b| *b == 0)
            .unwrap_or(request.len());
        let Ok(text) = std::str::from_utf8(&request[..end]) else {
            return Asked::Unreadable;
        };
        let mut words = text.split(' ');
        match words.next() {
            // getaddrinfo <name> <service> <flags> <family> <type> <protocol> <network>
            Some("getaddrinfo") => named(words.next(), Asked::Addresses),
            // gethostbyname <network> <name> <family>
            Some("gethostbyname") => named(words.nth(1), Asked::Name),
            // resnsend <network> <flags> <query, in base64>
            Some("resnsend") => words
                .nth(2)
                .and_then(base64)
                .and_then(|query| question(&query))
                .map_or(Asked::Unreadable, Asked::Name),
            // Not a lookup of a name: an address looked up, the network
            // asked for.
            Some("gethostbyaddr" | "getdnsnetid") => Asked::Other,
            _ => Asked::Unreadable,
        }
    }

    pub(super) fn host(&self) -> Option<&str> {
        match self {
            Asked::Addresses(host) | Asked::Name(host) => Some(host),
            Asked::Unreadable | Asked::Other => None,
        }
    }
}

/// Decode standard base64, with or without padding.
fn base64(text: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let (mut bits, mut have) = (0u32, 0u32);
    for byte in text.bytes() {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            _ => return None,
        };
        bits = (bits << 6) | u32::from(value);
        have += 6;
        if have >= 8 {
            have -= 8;
            out.push((bits >> have) as u8);
        }
    }
    Some(out)
}

/// The name a DNS query asks about: its first question.
fn question(query: &[u8]) -> Option<String> {
    // The header, then the name as labels, each with its length before it.
    let mut at = 12;
    let mut name = String::new();
    loop {
        let len = usize::from(*query.get(at)?);
        if len == 0 {
            break;
        }
        // A pointer to another place, which a question has no use for.
        if len > 63 {
            return None;
        }
        let label = query.get(at + 1..at + 1 + len)?;
        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(std::str::from_utf8(label).ok()?);
        at += 1 + len;
    }
    target::name(&name)
}

/// What can be read of an answer to `getaddrinfo` so far.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Parsed {
    /// All of it: the addresses, none where netd answered with an error.
    Whole(Vec<IpAddr>),
    /// More is to come.
    Partial,
    /// Not an answer as netd writes them.
    Unknown,
}

/// Read the addresses of an answer: `222`, a zero byte, then for each
/// address a 1, four numbers, the address as the system holds one (with
/// its length before it) and a name (with its length before it), and a 0
/// at the end. Numbers are four bytes, the first the highest. Any other
/// code than 222 is an error, followed by a length and that many bytes.
pub(super) fn addresses_in(answer: &[u8]) -> Parsed {
    let number = |at: usize| -> Option<usize> {
        let bytes: [u8; 4] = answer.get(at..at + 4)?.try_into().ok()?;
        Some(u32::from_be_bytes(bytes) as usize)
    };
    let Some(code) = answer.get(..4) else {
        return Parsed::Partial;
    };
    if code[3] != 0 || !code[..3].iter().all(u8::is_ascii_digit) {
        return Parsed::Unknown;
    }
    if &code[..3] != b"222" {
        return match number(4) {
            Some(len) if answer.len() >= 8 + len => Parsed::Whole(Vec::new()),
            _ => Parsed::Partial,
        };
    }
    let mut addresses = Vec::new();
    let mut at = 4;
    loop {
        match number(at) {
            None => return Parsed::Partial,
            Some(0) => return Parsed::Whole(addresses),
            Some(1) => {}
            Some(_) => return Parsed::Unknown,
        }
        // Flags, family, type and protocol; then the address.
        let Some(len) = number(at + 20) else {
            return Parsed::Partial;
        };
        let start = at + 24;
        let Some(address) = answer.get(start..start + len) else {
            return Parsed::Partial;
        };
        // The family in the machine's byte order, then for IPv4 the port
        // and the address, for IPv6 the port, four bytes and the address.
        match address.first().zip(address.get(1)) {
            Some((family, _)) if i32::from(*family) == libc::AF_INET && len >= 8 => {
                let v4: [u8; 4] = address[4..8].try_into().unwrap_or_default();
                addresses.push(IpAddr::V4(Ipv4Addr::from(v4)));
            }
            Some((family, _)) if i32::from(*family) == libc::AF_INET6 && len >= 24 => {
                let v6: [u8; 16] = address[8..24].try_into().unwrap_or_default();
                addresses.push(IpAddr::V6(Ipv6Addr::from(v6)).to_canonical());
            }
            _ => {}
        }
        let Some(name) = number(start + len) else {
            return Parsed::Partial;
        };
        at = start + len + 4 + name;
        if at > answer.len() {
            return Parsed::Partial;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_name_what_is_looked_up() {
        assert_eq!(
            Asked::parse(b"getaddrinfo www.Example.org ^ 1024 0 1 0 0\0"),
            Asked::Addresses("www.example.org".into())
        );
        assert_eq!(
            Asked::parse(b"gethostbyname 0 t.example.com 2\0"),
            Asked::Name("t.example.com".into())
        );
        // A query for example.com, as an app writes one.
        let query = [
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, b'e', b'x', b'a', b'm', b'p', b'l', b'e',
            3, b'c', b'o', b'm', 0, 0, 1, 0, 1,
        ];
        assert_eq!(question(&query), Some("example.com".into()));
        assert_eq!(
            Asked::parse(b"resnsend 0 0 EjQBAAABAAAAAAAAB2V4YW1wbGUDY29tAAABAAE=\0"),
            Asked::Name("example.com".into())
        );
        // What no rule can be about is passed on as it is.
        for request in [
            &b"getdnsnetid 0\0"[..],
            b"gethostbyaddr 203.0.113.7 4 2 0\0",
            b"getaddrinfo ^ ^ 0 0 0 0 0\0",
            b"getaddrinfo 203.0.113.7 ^ 4 0 1 0 0\0",
            b"getaddrinfo 2001:db8::5 ^ 4 0 1 0 0\0",
            b"getaddrinfo localhost ^ 0 0 1 0 0\0",
            b"gethostbyname 0 printer 2\0",
        ] {
            assert_eq!(Asked::parse(request), Asked::Other, "{request:?}");
        }
        // A lookup that cannot be read might be of a name netd reads.
        for request in [
            &b"resnsend 0 0 not!base64\0"[..],
            b"resnsend 0 0 EjQ=\0",
            b"resnsend 0 0\0",
            b"getaddrinfo exa\tmple.com ^ 0 0 1 0 0\0",
            b"getaddrinfo example..com ^ 0 0 1 0 0\0",
            b"getaddrinfo\0",
            b"gethostbyname 0\0",
            b"somethingnew example.com\0",
            b"\xff\xfe\0",
            b"",
        ] {
            assert_eq!(Asked::parse(request), Asked::Unreadable, "{request:?}");
        }
        assert_eq!(base64("aGVsbG8="), Some(b"hello".to_vec()));
        assert_eq!(base64("aGVsbG8"), Some(b"hello".to_vec()));
        // A question that points elsewhere, or runs off the end.
        assert_eq!(question(&[0; 12]), None);
        assert_eq!(
            question(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xc0, 12]),
            None
        );
        assert_eq!(
            question(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, b'a']),
            None
        );
    }

    /// One address of an answer, as netd writes it.
    fn entry(address: &[u8]) -> Vec<u8> {
        let mut out = vec![0, 0, 0, 1, 0, 0, 4, 0];
        out.extend([0, 0, 0, address[0], 0, 0, 0, 1, 0, 0, 0, 6]);
        out.extend((address.len() as u32).to_be_bytes());
        out.extend(address);
        out.extend([0, 0, 0, 0]);
        out
    }

    #[test]
    fn the_addresses_of_an_answer_are_read_once_it_is_whole() {
        let v4 = [2, 0, 0, 0, 203, 0, 113, 7, 0, 0, 0, 0, 0, 0, 0, 0];
        let mut v6 = vec![10, 0, 0, 0, 0, 0, 0, 0, 0x20, 0x01, 0x0d, 0xb8];
        v6.extend([0; 11]);
        v6.extend([5, 0, 0, 0, 0]);
        let mut answer = b"222\0".to_vec();
        answer.extend(entry(&v4));
        answer.extend(entry(&v6));
        // Every part of it short of the end is not all of it.
        for len in 0..answer.len() + 4 {
            let mut partial = answer.clone();
            partial.extend([0, 0, 0, 0]);
            partial.truncate(len);
            assert_eq!(addresses_in(&partial), Parsed::Partial, "{len} bytes");
        }
        answer.extend([0, 0, 0, 0]);
        assert_eq!(
            addresses_in(&answer),
            Parsed::Whole(vec![
                "203.0.113.7".parse().unwrap(),
                "2001:db8::5".parse().unwrap()
            ])
        );
        // No address: the name has none.
        assert_eq!(addresses_in(b"222\0\0\0\0\0"), Parsed::Whole(Vec::new()));
        // An error, whole once its bytes are there.
        assert_eq!(addresses_in(b"401\0\0\0\0\x04\0\0\0"), Parsed::Partial);
        assert_eq!(
            addresses_in(b"401\0\0\0\0\x04\0\0\0\x07"),
            Parsed::Whole(Vec::new())
        );
        // Not an answer of this form.
        assert_eq!(addresses_in(b"hello there"), Parsed::Unknown);
        assert_eq!(addresses_in(b"222\0\0\0\0\x09"), Parsed::Unknown);
    }
}
