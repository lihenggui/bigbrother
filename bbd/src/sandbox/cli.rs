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

use std::{collections::BTreeMap, io::Write as _, path::Path};

use crate::{
    control::{self, Request},
    sandbox_item::Item,
};

use super::{
    answers::{Configured, Disabled, DisabledAll, Enabled, GlobalItems, Listed, Status},
    appops, auto,
    items::{self, Part, Settings},
    items_of,
};

/// The app ops helper's lines that give every app in the sandbox its app
/// ops and the permissions it may have been taken back, read from the
/// database without the daemon: for `uninstall.sh`,
/// which may run before the daemon has started, or after it failed to take
/// every app out.
pub fn restore_lines(db: &Path) -> anyhow::Result<Vec<String>> {
    let reader = crate::store::Reader::open(db)?;
    let global = Settings::parse(&reader.sandbox_global()?);
    let mut pending: BTreeMap<_, _> = reader
        .appops_undo()?
        .into_iter()
        .map(|undo| (undo.uid, undo))
        .collect();
    let mut lines = Vec::new();
    let mut push = |package: &str, token: &str, command: &str| -> anyhow::Result<()> {
        anyhow::ensure!(
            token.len() == 32 && token.bytes().all(|c| c.is_ascii_hexdigit()),
            "recovery record has no verified installation"
        );
        lines.push(format!("bound {package} {token} {command}"));
        Ok(())
    };
    for entry in reader.sandbox_entries()?.iter().filter(|e| e.enabled) {
        let items = items_of(entry, &global);
        let undo = pending
            .remove(&entry.uid)
            .filter(|undo| undo.install_id == entry.install_id);
        if let Some(command) = appops::offline_restore(entry, &items, undo.as_ref()) {
            push(&entry.package, &entry.install_id, &command)?;
        }
    }
    for undo in pending.values() {
        push(&undo.package, &undo.install_id, &appops::undo_line(undo))?;
    }
    Ok(lines)
}

/// Send `request` to the daemon and print its answer: as the JSON line the
/// daemon sent with `json`, otherwise for a person to read.
pub fn command(db: &Path, request: Request, json: bool) -> anyhow::Result<()> {
    let answer = control::command(db, &request)?;
    if json {
        println!("{answer}");
        return Ok(());
    }
    let mut out = std::io::stdout().lock();
    match request {
        Request::SandboxEnable { package, .. } => {
            let enabled: Enabled = serde_json::from_value(answer)?;
            print_enabled(&mut out, &package, &enabled)
        }
        Request::SandboxDisable { package, .. } => {
            print_disabled(&mut out, &package, &serde_json::from_value(answer)?)
        }
        Request::SandboxDisableAll => {
            let all: DisabledAll = serde_json::from_value(answer)?;
            writeln!(out, "Sandbox disabled for {} apps.", all.disabled)?;
            if !all.failed.is_empty() {
                writeln!(out, "Still sandboxed: {} apps.", all.failed.len())?;
            }
            Ok(())
        }
        Request::SandboxList => print_list(&mut out, &serde_json::from_value(answer)?),
        Request::SandboxStatus => print_status(&mut out, &serde_json::from_value(answer)?),
        Request::SandboxSet { package, .. } => {
            print_configured(&mut out, &package, &serde_json::from_value(answer)?)
        }
        Request::SandboxGlobal | Request::SandboxSetGlobal { .. } => {
            let global: GlobalItems = serde_json::from_value(answer)?;
            print_items(&mut out, &global.items, |_| "")?;
            writeln!(out)?;
            auto::print(&mut out, global.auto)
        }
        Request::SandboxAuto | Request::SandboxSetAuto { .. } => {
            let switch: auto::Switch = serde_json::from_value(answer)?;
            auto::print(&mut out, switch.auto)
        }
        Request::NetBlock { .. } | Request::NetUnblock { .. } => writeln!(out, "Done."),
        Request::NetClear { .. } => writeln!(out, "Deleted {} rows.", answer["deleted"]),
    }?;
    Ok(())
}

fn print_enabled(
    out: &mut impl std::io::Write,
    package: &str,
    enabled: &Enabled,
) -> std::io::Result<()> {
    writeln!(out, "Sandbox enabled for {package} (uid {}).", enabled.uid)?;
    if !enabled.also.is_empty() {
        writeln!(
            out,
            "Also sandboxed, sharing its uid: {}.",
            enabled.also.join(", ")
        )?;
    }
    Ok(())
}

fn print_disabled(
    out: &mut impl std::io::Write,
    package: &str,
    disabled: &Disabled,
) -> std::io::Result<()> {
    if disabled.was_enabled {
        writeln!(out, "Sandbox disabled for {package}.")
    } else {
        writeln!(out, "{package} was not in the sandbox.")
    }
}

fn print_list(out: &mut impl std::io::Write, listed: &Listed) -> std::io::Result<()> {
    if listed.apps.is_empty() {
        return writeln!(out, "No apps in the sandbox.");
    }
    writeln!(out, "{:<10} {:<9} PACKAGE", "UID", "STATE")?;
    for app in &listed.apps {
        let state = if app.enabled { "enabled" } else { "disabled" };
        // What is not as an item is unless set otherwise.
        let not_on: Vec<String> = app
            .items
            .iter()
            .filter(|(item, mode)| *mode != item.default_mode())
            .map(|(item, mode)| format!("{} {}", item.name(), items::mode_name(mode)))
            .collect();
        let not_on = if not_on.is_empty() {
            String::new()
        } else {
            format!(" ({})", not_on.join(", "))
        };
        writeln!(out, "{:<10} {state:<9} {}{not_on}", app.uid, app.package)?;
        if app.appops_checking {
            writeln!(out, "{:<20} checking Android restrictions", "")?;
        }
        for (item, parts) in &app.unmet {
            let parts: Vec<&str> = parts
                .iter()
                .filter(|part| !app.appops_checking || **part != Part::AppOps)
                .map(|part| part.name())
                .collect();
            if parts.is_empty() {
                continue;
            }
            writeln!(
                out,
                "{:<20} {} not in effect, missing {}",
                "",
                item.name(),
                parts.join(", ")
            )?;
        }
    }
    Ok(())
}

/// Each item with its mode, and what `note` says of it.
fn print_items(
    out: &mut impl std::io::Write,
    items: &Settings,
    note: impl Fn(Item) -> &'static str,
) -> std::io::Result<()> {
    for (item, mode) in items.iter() {
        writeln!(
            out,
            "{:<20} {}{}",
            item.name(),
            items::mode_name(mode),
            note(item)
        )?;
    }
    Ok(())
}

fn print_configured(
    out: &mut impl std::io::Write,
    package: &str,
    configured: &Configured,
) -> std::io::Result<()> {
    writeln!(out, "Sandbox items for {package} (uid {}):", configured.uid)?;
    print_items(out, &configured.items, |item| {
        if configured.settings.get(item).is_some() {
            ""
        } else {
            " (global)"
        }
    })
}

fn print_status(out: &mut impl std::io::Write, status: &Status) -> std::io::Result<()> {
    let on = |active: bool| if active { "active" } else { "inactive" };
    writeln!(out, "Storage gate:      {}", on(status.gate))?;
    writeln!(out, "App ops:           {}", on(status.appops))?;
    writeln!(out, "Packet filter:     {}", on(status.network))?;
    writeln!(out, "ANDROID_ID hook:   {}", on(status.hook.android_id))?;
    writeln!(out, "App list hook:     {}", on(status.hook.app_list))?;
    writeln!(out, "File grants hook:  {}", on(status.hook.uri_grants))?;
    writeln!(out, "Ad ID hook:        {}", on(status.hook.ad_id))?;
    writeln!(out, "Starts admitted:   {}", status.admitted)?;
    writeln!(out, "Starts failed:     {}", status.failed)?;
    writeln!(out, "New apps held:     {}", on(status.new_apps_held))?;
    writeln!(out, "New apps stopped:  {}", status.new_apps_stopped)?;
    writeln!(out, "New apps pending:  {}", status.new_apps_pending)?;
    writeln!(out, "New apps failed:   {}", status.new_apps_failed)?;
    writeln!(out, "Hold time p95:     {} us", status.hold_p95_us)
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use super::*;
    use crate::sandbox::{Entry, answers::ListedApp, tests::Fixture};

    #[test]
    fn offline_recovery_includes_a_failed_enable_without_a_sandbox_row() {
        let f = crate::sandbox::tests::Fixture::new("offline-appops-undo");
        let token = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let preimage = "android:camera=4,android.permission.ACCESS_FINE_LOCATION=granted+user_set";
        f.writer
            .stage_appops(&crate::store::AppOpsUndo {
                uid: 10100,
                package: "com.app".into(),
                install_id: token.into(),
                preimage: preimage.into(),
                attempts: 0,
            })
            .unwrap();
        assert_eq!(
            restore_lines(&f.dir.join("bb.db")).unwrap(),
            [format!("bound com.app {token} undo 10100 {preimage}")]
        );
    }

    #[test]
    fn the_restore_lines_give_back_the_ops_and_the_permissions_taken() {
        let f = Fixture::new("sandbox-restore-lines");
        let entry = |uid, enabled, settings: &str, saved_permissions: &str| Entry {
            uid,
            package: "com.app".into(),
            enabled,
            android_id: "0123456789abcdef".into(),
            saved_ops: "android:camera=4".into(),
            install_id: "a".repeat(32),
            saved_permissions: saved_permissions.into(),
            created: 1000,
            settings: settings.into(),
        };
        let fine = items::PRECISE_LOCATION;
        let off: String = Item::ALL
            .iter()
            .map(|item| format!("{}=off", item.name()))
            .collect::<Vec<_>>()
            .join(",");
        f.writer.put_sandbox_global(&[("location", "off")]).unwrap();
        // Coarse, and the state the permission had saved.
        f.writer
            .put_sandbox(&entry(
                10100,
                true,
                &format!("{off},location=coarse"),
                &format!("{fine}=denied+user_set"),
            ))
            .unwrap();
        // Written down as the camera only while location changed from
        // coarse: the permission is given back all the same.
        f.writer
            .put_sandbox(&entry(
                10200,
                true,
                &format!("{off},camera=on"),
                &format!("{fine}=default"),
            ))
            .unwrap();
        // Every item off, and nothing taken: nothing to give back.
        f.writer.put_sandbox(&entry(10300, true, &off, "")).unwrap();
        // Out of the sandbox already.
        f.writer
            .put_sandbox(&entry(10400, false, "location=coarse", ""))
            .unwrap();
        assert_eq!(
            restore_lines(&f.dir.join("bb.db")).unwrap(),
            [
                format!(
                    "bound com.app aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa restore 10100 {fine}=denied+user_set"
                ),
                format!(
                    "bound com.app aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa restore 10200 android:camera=4,{fine}=default"
                ),
            ]
        );
    }

    #[test]
    fn answers_are_printed_for_a_person() {
        let mut out = Vec::new();
        print_enabled(
            &mut out,
            "com.shared.one",
            &Enabled {
                uid: 10200,
                also: vec!["com.shared.two".into()],
            },
        )
        .unwrap();
        print_list(
            &mut out,
            &Listed {
                hook_health: Default::default(),
                apps: vec![
                    ListedApp {
                        uid: 10100,
                        package: "com.app".into(),
                        enabled: false,
                        created: 0,
                        settings: Settings::default(),
                        items: Settings::default(),
                        unmet: BTreeMap::new(),
                        appops_checking: false,
                        network_rules: 0,
                        recording_since: None,
                    },
                    ListedApp {
                        uid: 10200,
                        package: "com.shared.one".into(),
                        enabled: true,
                        created: 0,
                        settings: Settings::parse("camera=off"),
                        items: Settings::parse("camera=off")
                            .over(&Settings::parse("location=coarse")),
                        unmet: BTreeMap::from([(
                            Item::DeviceIds,
                            vec![Part::AppOps, Part::AndroidIdHook],
                        )]),
                        appops_checking: true,
                        network_rules: 0,
                        recording_since: None,
                    },
                ],
            },
        )
        .unwrap();
        print_list(
            &mut out,
            &Listed {
                apps: vec![],
                hook_health: Default::default(),
            },
        )
        .unwrap();
        assert_eq!(
            String::from_utf8(out).unwrap(),
            "Sandbox enabled for com.shared.one (uid 10200).\n\
             Also sandboxed, sharing its uid: com.shared.two.\n\
             UID        STATE     PACKAGE\n\
             10100      disabled  com.app\n\
             10200      enabled   com.shared.one (location coarse, camera off)\n\
             \x20                    checking Android restrictions\n\
             \x20                    device_ids not in effect, missing android_id_hook\n\
             No apps in the sandbox.\n"
        );
    }
}
