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

//! How the query commands reach the running daemon.
//!
//! The daemon holds what it records for a while before writing it, so a
//! query first asks it to write, over a Unix socket next to the database.
//! The daemon takes in what the kernel and the hook have for it, writes, and
//! answers with one byte. A daemon that is not there, or is too old to have
//! the socket, is not waited for, and one that does not answer in time is
//! waited for only that long: the query then reads what has been written.

use std::{
    fs, io,
    os::unix::fs::PermissionsExt as _,
    path::{Path, PathBuf},
    time::Duration,
};

use tokio::net::{UnixListener, UnixStream};

/// The longest a query waits for the daemon to write.
const FLUSH_WAIT: Duration = Duration::from_secs(1);

fn socket_path(db: &Path) -> PathBuf {
    db.with_file_name("bbd.sock")
}

/// Written by the module's supervisor (module/supervise.sh).
fn pid_file(db: &Path) -> PathBuf {
    db.with_file_name("bbd.pid")
}

/// Whether the daemon recording into `db` is running: the process the
/// supervisor started is still there, and has its probes attached, which is
/// when it starts listening on its socket. A daemon still starting, or one
/// that fails to attach and exits, does not count. Unlike the time of the
/// last write, this does not depend on anything having been recorded lately.
pub fn daemon_running(db: &Path) -> bool {
    // The supervisor removes a socket left by a daemon that crashed before it
    // starts the next one, so a socket here is the running daemon's.
    if !socket_path(db).exists() {
        return false;
    }
    let Some(pid) = fs::read_to_string(pid_file(db))
        .ok()
        .and_then(|text| text.trim().parse::<u32>().ok())
    else {
        return false;
    };
    let comm = fs::read_to_string(format!("/proc/{pid}/comm")).unwrap_or_default();
    let cmdline = fs::read(format!("/proc/{pid}/cmdline")).unwrap_or_default();
    is_daemon(&comm, &cmdline)
}

/// A pid is handed to another process once its own has ended, possibly to a
/// query command, which is `bbd` too: it counts only while it is `bbd run`.
fn is_daemon(comm: &str, cmdline: &[u8]) -> bool {
    comm.trim_end() == "bbd" && cmdline.split(|&b| b == 0).nth(1) == Some(b"run")
}

/// Ask the daemon recording into `db` to write what it holds, and wait until
/// it has, or for [`FLUSH_WAIT`] at most.
pub fn request_flush(db: &Path) {
    request_flush_within(db, FLUSH_WAIT);
}

fn request_flush_within(db: &Path, wait: Duration) {
    let socket = socket_path(db);
    if !socket.exists() {
        return;
    }
    let Ok(runtime) = tokio::runtime::Builder::new_current_thread()
        .enable_io()
        .enable_time()
        .build()
    else {
        return;
    };
    // Whatever the outcome, the records are read next: a refused connection
    // (a socket left behind), a timeout and an answer all lead there.
    let _ = runtime.block_on(async {
        tokio::time::timeout(wait, async {
            let stream = UnixStream::connect(&socket).await?;
            read_byte(&stream).await
        })
        .await
    });
}

/// Wait for a byte to arrive, or for the other end to close.
async fn read_byte(stream: &UnixStream) -> io::Result<()> {
    loop {
        stream.readable().await?;
        match stream.try_read(&mut [0; 1]) {
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
            read => return read.map(drop),
        }
    }
}

/// Listen for flush requests for `db`.
pub fn listen(db: &Path) -> anyhow::Result<UnixListener> {
    let path = socket_path(db);
    // Left behind by a daemon that did not stop cleanly.
    let _ = fs::remove_file(&path);
    let listener = UnixListener::bind(&path)?;
    fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
    Ok(listener)
}

/// Remove the socket [`listen`] created.
pub fn unlisten(db: &Path) {
    let _ = fs::remove_file(socket_path(db));
}

/// Answer a flush request: what the daemon held is written.
pub fn answer(stream: &UnixStream) {
    // One byte into a new connection's empty buffer: this does not block.
    let _ = stream.try_write(&[1]);
}

#[cfg(test)]
mod tests {
    use std::time::Instant;

    use super::*;

    fn test_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("bbd-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Run a listener on `db`'s socket in a thread of its own; `serve` is
    /// given each connection.
    fn serve(
        db: &Path,
        serve: fn(UnixStream) -> Option<UnixStream>,
    ) -> std::thread::JoinHandle<()> {
        let db = db.to_path_buf();
        let (ready, listening) = std::sync::mpsc::channel();
        let thread = std::thread::spawn(move || {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap()
                .block_on(async {
                    let listener = listen(&db).unwrap();
                    ready.send(()).unwrap();
                    let (stream, _) = listener.accept().await.unwrap();
                    // Kept open until the client has gone.
                    if let Some(stream) = serve(stream) {
                        let _ = read_byte(&stream).await;
                    }
                });
        });
        listening.recv().unwrap();
        thread
    }

    #[test]
    fn a_query_does_not_wait_when_no_daemon_listens() {
        let dir = test_dir("control-absent");
        let db = dir.join("bb.db");
        let start = Instant::now();
        request_flush_within(&db, Duration::from_secs(5));
        // A socket left behind by a daemon that has gone.
        drop(std::os::unix::net::UnixListener::bind(socket_path(&db)).unwrap());
        request_flush_within(&db, Duration::from_secs(5));
        assert!(start.elapsed() < Duration::from_secs(1));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_query_returns_once_the_daemon_answers() {
        let dir = test_dir("control-answer");
        let db = dir.join("bb.db");
        let daemon = serve(&db, |stream| {
            answer(&stream);
            Some(stream)
        });
        let start = Instant::now();
        request_flush_within(&db, Duration::from_secs(5));
        assert!(start.elapsed() < Duration::from_secs(1));
        daemon.join().unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_query_waits_for_a_daemon_that_does_not_answer_only_so_long() {
        let dir = test_dir("control-silent");
        let db = dir.join("bb.db");
        let daemon = serve(&db, Some);
        let start = Instant::now();
        request_flush_within(&db, Duration::from_millis(200));
        let waited = start.elapsed();
        assert!(waited >= Duration::from_millis(200) && waited < Duration::from_secs(2));
        daemon.join().unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn only_the_recording_daemon_counts_as_running() {
        assert!(is_daemon(
            "bbd\n",
            b"/data/adb/modules/big_brother/bin/bbd\0run\0"
        ));
        // A query command that was handed the daemon's old pid.
        assert!(!is_daemon(
            "bbd\n",
            b"/data/adb/modules/big_brother/bin/bbd\0stats\0"
        ));
        assert!(!is_daemon("sh\n", b"sh\0run\0"));
        assert!(!is_daemon("", b""));

        let dir = test_dir("control-pid");
        let db = dir.join("bb.db");
        assert!(!daemon_running(&db));
        fs::write(pid_file(&db), format!("{}\n", std::process::id())).unwrap();
        assert!(!daemon_running(&db));
        fs::remove_dir_all(dir).unwrap();
    }
}
