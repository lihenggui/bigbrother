//! Turns the raw path an app passed to open() into a canonical shared-storage
//! path, or decides it isn't one.
//!
//! Shared storage is reachable under many names. All of these are the same
//! file for user 0:
//!   /sdcard/DCIM/a.jpg
//!   /storage/self/primary/DCIM/a.jpg
//!   /storage/emulated/0/DCIM/a.jpg
//!   /mnt/user/0/emulated/0/DCIM/a.jpg
//!   /data/media/0/DCIM/a.jpg
//! Everything is rewritten to the `/storage/...` form so the same file always
//! aggregates into one row.

/// Collapse `//`, `.` and `..` without touching the filesystem.
pub fn lexical_normalize(path: &str) -> String {
    let mut parts: Vec<&str> = Vec::new();
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            p => parts.push(p),
        }
    }
    let mut out = String::with_capacity(path.len());
    for p in parts {
        out.push('/');
        out.push_str(p);
    }
    if out.is_empty() {
        out.push('/');
    }
    out
}

/// If `path` is `prefix` or lies under it, return the remainder (either empty
/// or starting with '/').
fn under<'a>(path: &'a str, prefix: &str) -> Option<&'a str> {
    let rest = path.strip_prefix(prefix)?;
    (rest.is_empty() || rest.starts_with('/')).then_some(rest)
}

/// Drop the first path component of `rest` ("/0/emulated/0/x" -> "/emulated/0/x").
fn skip_component(rest: &str) -> Option<&str> {
    let rest = rest.strip_prefix('/')?;
    Some(rest.find('/').map_or("", |i| &rest[i..]))
}

/// Map an absolute path to canonical `/storage/...` form. `user` is the
/// Android user the calling process belongs to (uid / 100000), needed because
/// `/sdcard` means a different directory for each user.
///
/// Returns `None` when the path is not on shared storage.
pub fn canonicalize(absolute: &str, user: u32) -> Option<String> {
    let path = lexical_normalize(absolute);

    let storage_form: String = if under(&path, "/storage").is_some() {
        path
    } else if let Some(rest) = under(&path, "/sdcard").or_else(|| under(&path, "/mnt/sdcard")) {
        format!("/storage/emulated/{user}{rest}")
    } else if let Some(rest) = under(&path, "/data/media") {
        format!("/storage/emulated{rest}")
    } else if let Some(rest) = under(&path, "/mnt/user")
        .or_else(|| under(&path, "/mnt/pass_through"))
        .or_else(|| under(&path, "/mnt/runtime"))
        .or_else(|| under(&path, "/mnt/androidwritable"))
        .or_else(|| under(&path, "/mnt/installer"))
    {
        // /mnt/user/<user>/<volume>/..., /mnt/runtime/<mode>/<volume>/...
        format!("/storage{}", skip_component(rest)?)
    } else {
        format!("/storage{}", under(&path, "/mnt/media_rw")?)
    };

    let canonical = match under(&storage_form, "/storage/self/primary") {
        Some(rest) => format!("/storage/emulated/{user}{rest}"),
        None => storage_form,
    };

    // "/storage" and "/storage/emulated" themselves hold no user files.
    let depth = canonical.split('/').filter(|c| !c.is_empty()).count();
    let min_depth = if under(&canonical, "/storage/emulated").is_some() {
        3
    } else {
        2
    };
    (depth >= min_depth).then_some(canonical)
}

/// The top-level folder of a canonical shared-storage path, i.e. the first
/// component below the volume root: `DCIM` for
/// `/storage/emulated/0/DCIM/Camera/a.jpg`. `None` for the volume root itself.
pub fn top_level_folder(canonical: &str) -> Option<&str> {
    let mut parts = canonical.split('/').filter(|c| !c.is_empty());
    if parts.next() != Some("storage") {
        return None;
    }
    // Volume root is /storage/emulated/<user> or /storage/<volume-id>.
    if parts.next()? == "emulated" {
        parts.next()?;
    }
    parts.next()
}

/// True when a canonical path is inside the app-specific directory of one of
/// `packages` — i.e. the app is reading its own files, which needs no
/// permission and isn't interesting.
///
/// App-specific directories are `<volume>/Android/{data,obb,media}/<package>`.
pub fn is_own_dir(canonical: &str, packages: &[String]) -> bool {
    let mut parts = canonical.split('/').filter(|c| !c.is_empty());
    if parts.next() != Some("storage") {
        return false;
    }
    // Volume root is /storage/emulated/<user> or /storage/<volume-id>.
    if parts.next() == Some("emulated") {
        parts.next();
    }
    if parts.next() != Some("Android") {
        return false;
    }
    if !matches!(parts.next(), Some("data" | "obb" | "media")) {
        return false;
    }
    match parts.next() {
        Some(owner) => packages.iter().any(|p| p == owner),
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn canon(p: &str) -> Option<String> {
        canonicalize(p, 0)
    }

    #[test]
    fn aliases_collapse_to_one_form() {
        let want = Some("/storage/emulated/0/DCIM/a.jpg".to_string());
        for alias in [
            "/sdcard/DCIM/a.jpg",
            "/mnt/sdcard/DCIM/a.jpg",
            "/storage/self/primary/DCIM/a.jpg",
            "/storage/emulated/0/DCIM/a.jpg",
            "/mnt/user/0/emulated/0/DCIM/a.jpg",
            "/mnt/user/0/self/primary/DCIM/a.jpg",
            "/mnt/pass_through/0/emulated/0/DCIM/a.jpg",
            "/mnt/runtime/full/emulated/0/DCIM/a.jpg",
            "/mnt/androidwritable/0/emulated/0/DCIM/a.jpg",
            "/mnt/installer/0/emulated/0/DCIM/a.jpg",
            "/data/media/0/DCIM/a.jpg",
            "/storage//emulated/0/./Download/../DCIM/a.jpg",
        ] {
            assert_eq!(canon(alias), want, "{alias}");
        }
    }

    #[test]
    fn sdcard_follows_the_callers_user() {
        assert_eq!(
            canonicalize("/sdcard/x", 999).as_deref(),
            Some("/storage/emulated/999/x")
        );
        // An explicit user in the path is kept even if it isn't the caller's.
        assert_eq!(
            canonicalize("/storage/emulated/0/x", 999).as_deref(),
            Some("/storage/emulated/0/x")
        );
    }

    #[test]
    fn removable_volumes_are_kept() {
        assert_eq!(
            canon("/storage/1A2B-3C4D/Music/a.mp3").as_deref(),
            Some("/storage/1A2B-3C4D/Music/a.mp3")
        );
        assert_eq!(
            canon("/mnt/media_rw/1A2B-3C4D/Music/a.mp3").as_deref(),
            Some("/storage/1A2B-3C4D/Music/a.mp3")
        );
    }

    #[test]
    fn volume_roots_are_kept_but_containers_are_not() {
        assert_eq!(canon("/sdcard").as_deref(), Some("/storage/emulated/0"));
        assert_eq!(
            canon("/storage/emulated/0/").as_deref(),
            Some("/storage/emulated/0")
        );
        assert_eq!(canon("/storage"), None);
        assert_eq!(canon("/storage/emulated"), None);
    }

    #[test]
    fn non_storage_paths_are_rejected() {
        for p in [
            "/proc/self/status",
            "/data/data/com.x/files/a",
            "/sdcardfoo/a",
            "/storagex/a",
            "/mnt/vendor/persist/a",
            "/data/mediaserver/a",
            "/storage/../proc/cpuinfo",
            "/mnt/user",
        ] {
            assert_eq!(canon(p), None, "{p}");
        }
    }

    #[test]
    fn top_level_folder_is_the_first_name_below_the_volume() {
        for (path, want) in [
            ("/storage/emulated/0/DCIM/Camera/a.jpg", Some("DCIM")),
            ("/storage/emulated/0/Download", Some("Download")),
            ("/storage/emulated/0/notes.txt", Some("notes.txt")),
            ("/storage/1A2B-3C4D/Music/a.mp3", Some("Music")),
            ("/storage/emulated/0", None),
            ("/storage/1A2B-3C4D", None),
            ("/proc/cpuinfo", None),
        ] {
            assert_eq!(top_level_folder(path), want, "{path}");
        }
    }

    #[test]
    fn own_dirs_are_only_the_apps_android_dirs() {
        let pkgs = vec!["com.x".to_string(), "com.x.shared".to_string()];
        for own in [
            "/storage/emulated/0/Android/data/com.x",
            "/storage/emulated/0/Android/data/com.x/files/a",
            "/storage/emulated/10/Android/obb/com.x.shared/main.obb",
            "/storage/emulated/0/Android/media/com.x/a.jpg",
            "/storage/1A2B-3C4D/Android/data/com.x/cache",
        ] {
            assert!(is_own_dir(own, &pkgs), "{own}");
        }
        for foreign in [
            "/storage/emulated/0/Android/data/com.other/files/a",
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/data/com.xy/a",
            "/storage/emulated/0/DCIM/com.x/a.jpg",
            "/storage/emulated/0/Download/Android/data/com.x/a",
        ] {
            assert!(!is_own_dir(foreign, &pkgs), "{foreign}");
        }
    }
}
