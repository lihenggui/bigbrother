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

use std::io::{BufRead as _, BufReader};

use super::*;

pub(super) fn sandboxed(uid: u32) -> Entry {
    Entry {
        uid,
        package: "com.app".into(),
        enabled: true,
        android_id: String::new(),
        saved_ops: String::new(),
        install_id: String::new(),
        saved_permissions: String::new(),
        created: 0,
        settings: String::new(),
    }
}

pub(in crate::sandbox) const FINE: &str = items::PRECISE_LOCATION;

/// Every item off, but those `on` names, at their modes.
pub(super) fn only(on: &[(crate::sandbox_item::Item, crate::sandbox_item::Mode)]) -> Settings {
    use crate::sandbox_item::{Item, Mode};
    let mut own = Settings::default();
    for item in Item::ALL {
        own.set(item, Some(Mode::Off)).unwrap();
    }
    for (item, mode) in on {
        own.set(*item, Some(*mode)).unwrap();
    }
    own.over(&Settings::default())
}

#[test]
fn an_item_left_to_the_foreground_holds_its_ops_at_foreground() {
    use crate::sandbox_item::{Item, Mode};
    let mut appops = AppOps {
        shared: Arc::new(Shared::default()),
    };
    let (input, sent) = stand_in(&appops.shared, |_, _| {});
    appops.shared.state().input = Some(input);
    let on = only(&[(Item::Location, Mode::On), (Item::Camera, Mode::On)]);
    let foreground = only(&[
        (Item::Location, Mode::Foreground),
        (Item::Camera, Mode::Foreground),
    ]);
    let coarse = only(&[
        (Item::Location, Mode::Coarse),
        (Item::Camera, Mode::Foreground),
    ]);
    let mut entry = Entry {
        saved_ops: "android:wifi_scan=4,android:camera=default".into(),
        ..sandboxed(10100)
    };
    appops.resume(&[Resumed {
        entry: entry.clone(),
        packages: vec![],
        items: on.clone(),
    }]);

    appops.update(&mut entry, &[], &on, &foreground).unwrap();
    appops
        .update(&mut entry, &[], &foreground, &coarse)
        .unwrap();
    appops.disable(&mut entry, &[], &coarse).unwrap();
    assert!(appops.shared.state().input.take().is_some());
    assert_eq!(
        sent.join().unwrap(),
        [
            "verify 10100 android:coarse_location=1,android:fine_location=1,android:wifi_scan=1,android:camera=1".to_owned(),
            "apply 10100 android:coarse_location=4,android:fine_location=4,\
             android:wifi_scan=4,android:camera=4 -"
                .to_string(),
            format!(
                "apply 10100 android:camera=4,{FINE}=revoked \
                 android:coarse_location=default,android:fine_location=default,android:wifi_scan=4"
            ),
            format!("restore 10100 android:camera=default,{FINE}=granted"),
        ]
    );
}

#[test]
fn keeps_the_first_saved_state_of_a_permission() {
    // What the helper finds is kept only where nothing was named: for
    // a permission named already, it is the helper's own doing.
    let mut entry = sandboxed(10100);
    keep_found(&mut entry, &format!("{FINE}=granted"));
    assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
    keep_found(&mut entry, &format!("{FINE}=denied+user_fixed"));
    assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
    let mut leftover = Entry {
        saved_permissions: format!("{FINE}=default"),
        ..sandboxed(10100)
    };
    keep_found(&mut leftover, &format!("{FINE}=denied+user_fixed"));
    assert_eq!(leftover.saved_permissions, format!("{FINE}=default"));
    // An op is never taken for a permission.
    keep_found(&mut entry, "android:camera=1");
    assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
    forget_given_back(&mut entry, &[FINE.to_string()]);
    assert_eq!(entry.saved_permissions, "");
}

/// Stands in for the helper: `cat`, which gives back every line it was
/// sent, in the order the helper would take them. Each command is
/// answered as the helper would answer it, after `meanwhile` was given
/// the state, as it is while the command waits for its answer.
pub(super) fn stand_in(
    shared: &Arc<Shared>,
    mut meanwhile: impl FnMut(&mut State, u32) + Send + 'static,
) -> (Pipe, std::thread::JoinHandle<Vec<String>>) {
    scripted_helper(shared, move |state, uid, command, answer| {
        if !command.starts_with("capture ") {
            meanwhile(state, uid);
        }
        answer
    })
}

fn scripted_helper(
    shared: &Arc<Shared>,
    mut answer_for: impl FnMut(&mut State, u32, &str, Option<Answer>) -> Option<Answer> + Send + 'static,
) -> (Pipe, std::thread::JoinHandle<Vec<String>>) {
    let mut cat = std::process::Command::new("cat")
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .spawn()
        .unwrap();
    let input = cat.stdin.take().unwrap();
    let output = cat.stdout.take().unwrap();
    let shared = shared.clone();
    let sent = std::thread::spawn(move || {
        let mut sent = Vec::new();
        let mut captured = None;
        for line in BufReader::new(output).lines().map_while(Result::ok) {
            let line = line.strip_prefix("guard ").unwrap();
            let command = line
                .strip_prefix("bound ")
                .and_then(|bound| bound.splitn(3, ' ').nth(2))
                .unwrap_or(line);
            let command = command
                .strip_prefix("request ")
                .and_then(|request| request.split_once(' ').map(|(_, command)| command))
                .unwrap_or(command);
            let mut words = command.split(' ');
            let kind = words.next().unwrap().to_string();
            let uid = words.next().unwrap().parse().unwrap();
            sent.push(command.to_owned());
            // Each permission taken was granted, as far as it says.
            let found = names_in(words.next().unwrap_or(""))
                .into_iter()
                .filter(|name| is_permission(name))
                .map(|name| format!("{name}=granted"))
                .collect::<Vec<_>>()
                .join(",");
            let answer = match kind.as_str() {
                "capture" => {
                    let request = command.split(' ').nth(2).unwrap().parse().unwrap();
                    let saved = format!("android:camera=4,{FINE}=granted+user_set");
                    captured = Some((uid, saved.clone()));
                    Answer::Captured(request, Some(saved))
                }
                "set" => Answer::Saved(
                    captured
                        .take()
                        .filter(|(id, _)| *id == uid)
                        .map_or(found, |(_, saved)| saved),
                ),
                "apply" => Answer::Applied(found),
                "restore" | "undo" | "check-undo" => Answer::Restored,
                _ => continue,
            };
            let mut state = shared.state();
            state.answer = answer_for(&mut state, uid, command, Some(answer));
            shared.changed.notify_all();
        }
        let _ = cat.wait();
        sent
    });
    (Pipe::new(input).unwrap(), sent)
}

#[test]
fn original_app_ops_and_permission_flags_are_committed_before_set() {
    use crate::{
        packages::Packages,
        sandbox::Sandbox,
        store::{Reader, Writer},
    };

    let dir = std::env::temp_dir().join(format!("bbd-appops-capture-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let list = dir.join("packages.list");
    std::fs::write(
        &list,
        "com.example.reader 10100 0 /data/user/0/com.example.reader default none\n",
    )
    .unwrap();
    let mut packages = Packages::new(list.to_str().unwrap());
    let db = dir.join("bb.db");
    let writer = Writer::open(&db).unwrap();
    writer
        .put_sandbox_global(&[("location", "coarse")])
        .unwrap();
    let shared = Arc::new(Shared::default());
    let mut sandbox = Sandbox::load(
        &writer,
        vec![Box::new(AppOps {
            shared: shared.clone(),
        })],
        None,
    )
    .unwrap();
    let fault = rusqlite::Connection::open(&db).unwrap();
    fault
        .execute_batch(
            "CREATE TRIGGER fail_enable BEFORE INSERT ON sandbox_appops_undo
             BEGIN SELECT RAISE(FAIL, 'injected write failure'); END;",
        )
        .unwrap();
    let (input, sent) = stand_in(&shared, |_, _| panic!("the helper must only read"));
    shared.state().input = Some(input);
    assert!(
        sandbox
            .enable(&writer, &mut packages, "com.example.reader", 0, 1000)
            .is_err()
    );
    assert!(writer.sandbox_entry(10100).unwrap().is_none());
    assert!(shared.state().input.take().is_some());
    let sent = sent.join().unwrap();
    assert!(sent[0].starts_with("capture 10100 "));
    assert!(sent[1].starts_with("forget 10100 "));
    assert_eq!(sent.len(), 2);
    fault.execute_batch("DROP TRIGGER fail_enable").unwrap();
    drop(fault);

    let witness = db.clone();
    let (input, sent) = stand_in(&shared, move |_, uid| {
        // Open a new connection as a restarted daemon would see it.
        let reader = Reader::open(&witness).unwrap();
        assert!(reader.sandbox_entries().unwrap().is_empty());
        let journal = reader.appops_undo().unwrap();
        assert_eq!(journal.len(), 1);
        assert_eq!(journal[0].uid, uid);
        assert_eq!(
            journal[0].preimage,
            format!("android:camera=4,{FINE}=granted+user_set")
        );
    });
    shared.state().input = Some(input);
    sandbox
        .enable(&writer, &mut packages, "com.example.reader", 0, 1000)
        .unwrap();
    let entry = writer.sandbox_entry(10100).unwrap().unwrap();
    assert_eq!(entry.saved_ops, "android:camera=4");
    assert_eq!(entry.saved_permissions, format!("{FINE}=granted+user_set"));
    shared.state().input = None;
    let sent = sent.join().unwrap();
    assert!(writer.appops_undo().unwrap().is_empty());
    assert!(sent[0].starts_with("capture 10100 "));
    assert!(sent[1].starts_with("set 10100 "));
    drop(writer);
    std::fs::remove_dir_all(dir).unwrap();
}

/// The camera and the microphone on, as `camera` says, and every other
/// item off.
pub(super) fn camera_and_microphone(camera: bool) -> Settings {
    use crate::sandbox_item::{Item, Mode};
    let mut own = Settings::default();
    for item in Item::ALL {
        own.set(item, Some(Mode::Off)).unwrap();
    }
    let on = |yes| if yes { Mode::On } else { Mode::Off };
    own.set(Item::Camera, Some(on(camera))).unwrap();
    own.set(Item::Microphone, Some(Mode::On)).unwrap();
    own.over(&Settings::default())
}

#[test]
fn an_app_starting_while_its_ops_change_is_not_held_to_what_they_were() {
    let mut appops = AppOps {
        shared: Arc::new(Shared::default()),
    };
    let (both, microphone) = (camera_and_microphone(true), camera_and_microphone(false));
    let mut entry = sandboxed(10100);
    appops
        .shared
        .state()
        .guarded
        .insert(10100, targets_of(&both));
    // The app starts, and the gate has its ops looked at, while each
    // command is on its way.
    let (input, sent) = stand_in(&appops.shared, |state, uid| {
        state.verify(uid).unwrap();
    });
    appops.shared.state().input = Some(input);

    // The camera turns off and the app leaves while the helper stays available.
    appops.update(&mut entry, &[], &both, &microphone).unwrap();
    appops.verifier()(10100);
    appops.disable(&mut entry, &[], &microphone).unwrap();
    assert!(appops.shared.state().input.take().is_some());
    assert_eq!(
        sent.join().unwrap(),
        [
            "apply 10100 android:record_audio=1 android:camera=default",
            "verify 10100 android:record_audio=1",
            "restore 10100 android:record_audio=default",
        ]
    );
}

#[test]
fn a_helper_started_while_a_command_waits_uses_the_committed_policy() {
    let mut appops = AppOps {
        shared: Arc::new(Shared::default()),
    };
    let (both, microphone) = (camera_and_microphone(true), camera_and_microphone(false));
    let mut entry = sandboxed(10100);
    {
        let mut state = appops.shared.state();
        state.guarded.insert(10100, targets_of(&both));
        state.guarded.insert(10200, targets_of(&microphone));
    }
    // The helper ends after it applied the change and answered, and
    // the next one starts before the answer is taken.
    let (next, sent_next) = stand_in(&appops.shared, |_, _| {});
    let mut next = Some(next);
    let (input, sent) = stand_in(&appops.shared, move |state, _| {
        state.input = next.take();
        state.tell_guarded();
    });
    appops.shared.state().input = Some(input);

    appops.update(&mut entry, &[], &both, &microphone).unwrap();
    appops.shared.state().input = None;
    assert_eq!(
        sent.join().unwrap(),
        ["apply 10100 android:record_audio=1 android:camera=default"]
    );
    assert_eq!(
        sent_next.join().unwrap(),
        [
            "verify 10200 android:record_audio=1",
            "verify 10100 android:camera=1,android:record_audio=1",
        ]
    );
}

#[test]
fn location_made_coarse_and_back_saves_and_gives_back_the_permission() {
    use crate::sandbox_item::{Item, Mode};
    let mut appops = AppOps {
        shared: Arc::new(Shared::default()),
    };
    let (input, sent) = stand_in(&appops.shared, |_, _| {});
    appops.shared.state().input = Some(input);
    let coarse = only(&[(Item::Location, Mode::Coarse), (Item::Camera, Mode::On)]);
    let on = only(&[(Item::Location, Mode::On), (Item::Camera, Mode::On)]);
    let mut entry = sandboxed(10100);

    appops.enable(&mut entry, &[], &coarse).unwrap();
    assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
    // Given back as it was when the location is no longer coarse...
    appops.update(&mut entry, &[], &coarse, &on).unwrap();
    assert_eq!(entry.saved_permissions, "");
    // ...and saved anew when it is again, as the user may have changed
    // it in between.
    appops.update(&mut entry, &[], &on, &coarse).unwrap();
    assert_eq!(entry.saved_permissions, format!("{FINE}=granted"));
    appops.disable(&mut entry, &[], &coarse).unwrap();
    assert!(appops.shared.state().input.take().is_some());
    assert_eq!(entry.saved_permissions, "");
    assert_eq!(
        sent.join().unwrap(),
        [
            format!("set 10100 android:camera=1,{FINE}=revoked"),
            format!(
                "apply 10100 android:coarse_location=1,android:fine_location=1,\
                 android:wifi_scan=1,android:camera=1 {FINE}=granted"
            ),
            format!(
                "apply 10100 android:camera=1,{FINE}=revoked \
                 android:coarse_location=default,android:fine_location=default,\
                 android:wifi_scan=default"
            ),
            format!("restore 10100 android:camera=default,{FINE}=granted"),
        ]
    );
}

#[test]
fn a_permission_named_is_given_back_whatever_the_items_say() {
    use crate::sandbox_item::{Item, Mode};
    let mut appops = AppOps {
        shared: Arc::new(Shared::default()),
    };
    let (input, sent) = stand_in(&appops.shared, |_, _| {});
    appops.shared.state().input = Some(input);
    // A daemon that stopped while location was made coarse from on,
    // written down as on, and the state not yet known.
    let on = only(&[(Item::Location, Mode::On)]);
    let off = only(&[]);
    let mut entry = Entry {
        saved_permissions: format!("{FINE}=default"),
        ..sandboxed(10100)
    };
    assert_eq!(
        restore_line(&entry, &on).as_deref(),
        Some(
            "restore 10100 android:coarse_location=default,android:fine_location=default,\
             android:wifi_scan=default,android.permission.ACCESS_FINE_LOCATION=default"
        )
    );
    assert_eq!(
        restore_line(&entry, &off).as_deref(),
        Some("restore 10100 android.permission.ACCESS_FINE_LOCATION=default")
    );
    appops.resume(&[Resumed {
        entry: entry.clone(),
        packages: vec![],
        items: on.clone(),
    }]);
    appops.update(&mut entry, &[], &on, &off).unwrap();
    assert!(appops.shared.state().input.take().is_some());
    assert_eq!(entry.saved_permissions, "");
    assert_eq!(
        sent.join().unwrap(),
        [
            "verify 10100 android:coarse_location=1,android:fine_location=1,android:wifi_scan=1"
                .to_owned(),
            format!(
                "restore 10100 android:coarse_location=default,android:fine_location=default,\
             android:wifi_scan=default,{FINE}=default"
            )
        ]
    );
}

#[test]
fn an_app_is_given_back_the_ops_it_is_held_to_at_their_saved_modes() {
    use crate::sandbox_item::{Item, Mode};
    let entry = Entry {
        saved_ops: "android:camera=4,android:read_sms=1".into(),
        ..sandboxed(10100)
    };
    // The processor information holds no op: nothing to give back.
    assert_eq!(
        restore_line(&entry, &only(&[(Item::CpuInfo, Mode::On)])),
        None
    );
    assert_eq!(
        restore_line(&entry, &only(&[(Item::Camera, Mode::On)])).as_deref(),
        Some("restore 10100 android:camera=4")
    );
    assert_eq!(
        restore_line(
            &entry,
            &only(&[(Item::Camera, Mode::On), (Item::Microphone, Mode::On)])
        )
        .as_deref(),
        Some("restore 10100 android:camera=4,android:record_audio=default")
    );
    // The precise location permission at the state saved for it.
    let coarse = Entry {
        saved_permissions: format!("{FINE}=denied+user_set"),
        ..entry
    };
    assert_eq!(
        restore_line(&coarse, &only(&[(Item::Location, Mode::Coarse)])).as_deref(),
        Some("restore 10100 android.permission.ACCESS_FINE_LOCATION=denied+user_set")
    );
}

#[test]
fn ops_saved_with_their_grants_go_back_with_them() {
    use crate::sandbox_item::{Item, Mode};
    let (ops, permissions) = split_saved(&format!(
        "android:read_contacts=1/1,android:camera=default/4,{FINE}=granted"
    ));
    assert_eq!(ops, "android:read_contacts=1/1,android:camera=default/4");
    assert_eq!(permissions, format!("{FINE}=granted"));
    let entry = Entry {
        saved_ops: ops,
        ..sandboxed(10100)
    };
    // The helper sets each op by its grant then and now.
    assert_eq!(
        restore_line(
            &entry,
            &only(&[(Item::Camera, Mode::On), (Item::Contacts, Mode::On)])
        )
        .as_deref(),
        Some(
            "restore 10100 android:read_contacts=1/1,android:write_contacts=default,\
             android:camera=default/4"
        )
    );
}

#[test]
fn a_service_given_the_access_again_keeps_the_others_that_had_it() {
    let access = items::NOTIFICATION_ACCESS;
    let both = format!("{access}=granted+service:a:a.A+service:a:a.B");
    let mut entry = Entry {
        saved_permissions: both.clone(),
        ..sandboxed(10100)
    };
    // One of the two given it again: both keep it.
    assert!(!keep_regranted(
        &mut entry,
        &[format!("{access}=granted+service:a:a.A")]
    ));
    assert_eq!(entry.saved_permissions, both);
    // One that did not have it, given it by the user: it joins them.
    assert!(keep_regranted(
        &mut entry,
        &[format!("{access}=granted+service:a:a.C")]
    ));
    assert_eq!(entry.saved_permissions, format!("{both}+service:a:a.C"));
    // Nothing known before: what was found.
    let mut unknown = Entry {
        saved_permissions: format!("{access}=default"),
        ..sandboxed(10100)
    };
    assert!(keep_regranted(
        &mut unknown,
        &[format!("{access}=granted+service:a:a.A")]
    ));
    assert_eq!(
        unknown.saved_permissions,
        format!("{access}=granted+service:a:a.A")
    );
}

#[test]
fn a_permission_granted_again_while_held_is_given_back_granted() {
    use crate::sandbox_item::{Item, Mode};
    let mut appops = AppOps {
        shared: Arc::new(Shared::default()),
    };
    let entry = Entry {
        saved_permissions: format!("{FINE}=denied+user_set"),
        ..sandboxed(10100)
    };
    appops.shared.bind(10100, &entry.package, &entry.install_id);
    appops
        .shared
        .state()
        .guarded
        .insert(10100, format!("{FINE}=revoked"));
    let mut entries = HashMap::from([(10100, entry), (10101, sandboxed(10101))]);
    appops
        .shared
        .receive(&format!("reverted 10100 {FINE}=granted+user_set"));
    // Not taken from this one, nor is there an entry for the last.
    appops
        .shared
        .receive(&format!("reverted 10101 {FINE}=granted"));
    appops
        .shared
        .receive(&format!("reverted 10102 {FINE}=granted"));

    assert_eq!(appops.found(&mut entries), [10100]);
    assert!(appops.shared.state().found.is_empty());
    assert_eq!(entries[&10101].saved_permissions, "");
    assert_eq!(
        restore_line(&entries[&10100], &only(&[(Item::Location, Mode::Coarse)])).as_deref(),
        Some("restore 10100 android.permission.ACCESS_FINE_LOCATION=granted+user_set")
    );
    // The helper still needs a receipt for its observation.
    appops
        .shared
        .state()
        .found
        .push((10100, format!("{FINE}=granted+user_set")));
    assert_eq!(appops.found(&mut entries), [10100]);
}

#[test]
fn a_lost_set_reply_survives_helper_and_daemon_restart_until_undo_is_checked() {
    use crate::sandbox::tests::Fixture;

    let mut f = Fixture::new("appops-lost-set");
    f.writer
        .put_sandbox_global(&[("location", "coarse")])
        .unwrap();
    let original = format!("android:camera=4,{FINE}=granted+user_set");
    let android = Arc::new(Mutex::new(original.clone()));
    let shared = Arc::new(Shared::default());
    let mut sandbox = f.sandbox_with(vec![Box::new(AppOps {
        shared: shared.clone(),
    })]);
    let (input, ran) = scripted_helper(&shared, {
        let android = android.clone();
        move |state, _, command, reply| {
            if command.starts_with("set ") {
                // The op was changed, then the helper died before revoking location.
                *android.lock().unwrap() = format!("android:camera=1,{FINE}=granted+user_set");
                state.input.take();
                return None;
            }
            reply
        }
    });
    shared.state().input = Some(input);
    assert!(
        sandbox
            .enable(&f.writer, &mut f.packages, "com.app", 0, 0)
            .is_err()
    );
    assert!(f.writer.sandbox_entry(10100).unwrap().is_none());
    assert_eq!(f.writer.appops_undo().unwrap()[0].preimage, original);
    assert_eq!(ran.join().unwrap().len(), 2);
    drop(sandbox);

    let shared = Arc::new(Shared::default());
    let mut restarted = f.sandbox_with(vec![Box::new(AppOps {
        shared: shared.clone(),
    })]);
    restarted.resume(&f.writer, &mut f.packages);
    let (input, failed) = scripted_helper(&shared, |state, _, command, _| {
        assert!(command.starts_with("undo 10100 "));
        state.input.take();
        None
    });
    shared.state().input = Some(input);
    restarted.recover_appops(&f.writer, &mut f.packages);
    assert_eq!(failed.join().unwrap().len(), 1);
    assert_eq!(f.writer.appops_undo().unwrap()[0].preimage, original);
    assert_ne!(*android.lock().unwrap(), original);

    let (input, recovered) = scripted_helper(&shared, {
        let android = android.clone();
        move |_, _, command, answer| {
            let values = command.strip_prefix("undo 10100 ").unwrap();
            *android.lock().unwrap() = values.to_owned();
            answer
        }
    });
    shared.state().input = Some(input);
    restarted.recover_appops(&f.writer, &mut f.packages);
    assert!(f.writer.appops_undo().unwrap().is_empty());
    assert!(f.writer.sandbox_entry(10100).unwrap().is_none());
    assert_eq!(*android.lock().unwrap(), original);
    assert!(shared.state().input.take().is_some());
    let sent = recovered.join().unwrap();
    assert_eq!(
        sent.iter()
            .filter(|line| !line.starts_with("forget "))
            .count(),
        1
    );
}

#[test]
fn stale_replies_are_typed_for_set_apply_and_restore() {
    let mut appops = AppOps::new();
    let (input, ran) = scripted_helper(&appops.shared, |_, _, _, _| Some(Answer::Stale));
    appops.shared.state().input = Some(input);
    let mut entry = sandboxed(10100);
    let original = entry.clone();
    let items = camera_and_microphone(true);
    let after = camera_and_microphone(false);

    for result in [
        appops.enable(&mut entry, &[], &items),
        appops.update(&mut entry, &[], &items, &after),
        appops.disable(&mut entry, &[], &items),
    ] {
        assert!(result.unwrap_err().is::<StalePreimage>());
    }
    assert_eq!(entry, original);
    assert!(!appops.shared.state().restart);
    drop(appops);
    let kinds: Vec<_> = ran
        .join()
        .unwrap()
        .into_iter()
        .map(|line| line.split_ascii_whitespace().next().unwrap().to_owned())
        .collect();
    assert_eq!(kinds, ["set", "apply", "restore"]);
}

pub(in crate::sandbox) fn helper_rejecting_stale_changes(
    mut is_stale: impl FnMut(&str) -> bool + Send + 'static,
) -> (Box<dyn Effect>, std::thread::JoinHandle<Vec<String>>) {
    let shared = Arc::new(Shared::default());
    let (input, thread) = scripted_helper(&shared, move |_, _, command, answer| {
        if is_stale(command) {
            Some(Answer::Stale)
        } else {
            answer
        }
    });
    shared.state().input = Some(input);
    (Box::new(AppOps { shared }), thread)
}

pub(in crate::sandbox) fn recording_helper(
    mut observe: impl FnMut(u32, &str) + Send + 'static,
) -> (Box<dyn Effect>, std::thread::JoinHandle<Vec<String>>) {
    let shared = Arc::new(Shared::default());
    let (input, thread) = scripted_helper(&shared, move |_, uid, command, answer| {
        observe(uid, command);
        answer
    });
    shared.state().input = Some(input);
    (Box::new(AppOps { shared }), thread)
}
