//! Personal data kept by system content providers: contacts, the call log,
//! messages, the calendar and SIM details.
//!
//! The LSPosed hook reports every query and open that arrives at these
//! providers (see `spool`). Which collection was asked for is in the URI's
//! authority; the rest of the URI names individual people and messages and
//! is not kept. A record is stored as `content://<authority>/<collection>`.

use crate::rules::topic;

const SCHEME: &str = "content://";

/// The authority of a content URI, without a user prefix (`10@sms`).
fn authority(uri: &str) -> Option<&str> {
    let rest = uri.strip_prefix(SCHEME)?;
    let authority = &rest[..rest.find(['/', '?', '#']).unwrap_or(rest.len())];
    Some(authority.rsplit('@').next().unwrap_or(authority))
}

/// What the collection behind `uri` holds. `None` if it is not personal
/// data (media, documents, anything else).
pub fn topic_of(uri: &str) -> Option<&'static str> {
    Some(match authority(uri)? {
        // `icc` is the address book stored on the SIM.
        "com.android.contacts" | "contacts" | "icc" => topic::CONTACTS,
        "call_log" | "call_log_shadow" | "com.android.voicemail" => topic::CALL_LOG,
        "sms" | "mms" | "mms-sms" => topic::MESSAGES,
        "com.android.calendar" => topic::CALENDAR,
        "telephony" => topic::SIM_INFO,
        _ => return None,
    })
}

/// What to store for a personal-data URI: its authority and first path
/// segment, which names the collection (`content://sms/inbox`).
pub fn collection(uri: &str) -> Option<String> {
    topic_of(uri)?;
    let authority = authority(uri)?;
    let path = &uri[SCHEME.len()..];
    let path = &path[..path.find(['?', '#']).unwrap_or(path.len())];
    let segment = path
        .split('/')
        .nth(1)
        .filter(|s| !s.is_empty() && !s.bytes().all(|b| b.is_ascii_digit()));
    Some(match segment {
        Some(segment) => format!("{SCHEME}{authority}/{segment}"),
        None => format!("{SCHEME}{authority}"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collections_and_topics() {
        for (uri, stored, topic) in [
            (
                "content://com.android.contacts/data/phones?limit=5",
                "content://com.android.contacts/data",
                topic::CONTACTS,
            ),
            (
                "content://com.android.contacts/contacts/lookup/0r1-2A/17/photo",
                "content://com.android.contacts/contacts",
                topic::CONTACTS,
            ),
            ("content://icc/adn", "content://icc/adn", topic::CONTACTS),
            (
                "content://call_log/calls",
                "content://call_log/calls",
                topic::CALL_LOG,
            ),
            (
                "content://sms/inbox",
                "content://sms/inbox",
                topic::MESSAGES,
            ),
            ("content://sms/42", "content://sms", topic::MESSAGES),
            ("content://sms", "content://sms", topic::MESSAGES),
            (
                "content://10@mms-sms/conversations",
                "content://mms-sms/conversations",
                topic::MESSAGES,
            ),
            (
                "content://com.android.calendar/events",
                "content://com.android.calendar/events",
                topic::CALENDAR,
            ),
            (
                "content://telephony/siminfo",
                "content://telephony/siminfo",
                topic::SIM_INFO,
            ),
        ] {
            assert_eq!(collection(uri).as_deref(), Some(stored), "{uri}");
            assert_eq!(topic_of(stored), Some(topic), "{stored}");
        }
    }

    #[test]
    fn other_providers_are_not_personal_data() {
        for uri in [
            "content://media/external/images/media",
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM",
            "content://user_dictionary/words",
            "content://smsx/inbox",
            "/proc/cpuinfo",
            "content://",
        ] {
            assert_eq!(topic_of(uri), None, "{uri}");
            assert_eq!(collection(uri), None, "{uri}");
        }
    }
}
