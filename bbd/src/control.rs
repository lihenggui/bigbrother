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
//! answers with one byte. A daemon that is not there is not waited for, and
//! one that does not answer in time is waited for only that long: the query
//! then reads what has been written.
//!
//! Commands that change what the daemon does (the sandbox) go over a second
//! socket, `bbd.ctl`, as one line of JSON each way. It is separate so that
//! the flush socket keeps its one-byte protocol: a client that sends nothing
//! there still gets its byte.

use std::{
    collections::BTreeMap,
    fs,
    io::{self, BufRead as _, Write as _},
    os::unix::fs::PermissionsExt as _,
    path::{Path, PathBuf},
    time::Duration,
};

use anyhow::Context as _;
use serde::{Deserialize, Serialize};
use tokio::net::{UnixListener, UnixStream};

use crate::sandbox::items::{Item, Mode};

/// The longest a query waits for the daemon to write.
const FLUSH_WAIT: Duration = Duration::from_secs(1);

/// The longest a command waits for the daemon's answer. Generous: carrying
/// out a command may mean stopping apps and asking system services.
pub const COMMAND_WAIT: Duration = Duration::from_secs(10);

/// The longest the daemon waits for a connected client to send its request,
/// and for an answer to be taken.
const REQUEST_WAIT: Duration = Duration::from_secs(2);

/// The longest request line the daemon reads. Real ones are well under 200
/// bytes.
const MAX_REQUEST: usize = 4096;

fn socket_path(db: &Path) -> PathBuf {
    db.with_file_name("bbd.sock")
}

fn command_socket_path(db: &Path) -> PathBuf {
    db.with_file_name("bbd.ctl")
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

// ---- commands --------------------------------------------------------------

/// A command, as sent over `bbd.ctl`: `{"cmd":"sandbox.enable",...}`.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "cmd")]
// Named after their `cmd`; commands of other kinds will follow.
#[allow(clippy::enum_variant_names)]
pub enum Request {
    #[serde(rename = "sandbox.enable")]
    SandboxEnable {
        package: String,
        #[serde(default)]
        user: u32,
    },
    #[serde(rename = "sandbox.disable")]
    SandboxDisable {
        package: String,
        #[serde(default)]
        user: u32,
    },
    /// Take every app out: what is done before the module is removed.
    #[serde(rename = "sandbox.disable_all")]
    SandboxDisableAll,
    #[serde(rename = "sandbox.list")]
    SandboxList,
    #[serde(rename = "sandbox.status")]
    SandboxStatus,
    /// Set what the sandbox does to an app, item by item: to a mode, or
    /// with `null` back to following the global settings. Items not named
    /// keep what they have.
    #[serde(rename = "sandbox.set")]
    SandboxSet {
        package: String,
        #[serde(default)]
        user: u32,
        settings: BTreeMap<Item, Option<Mode>>,
    },
    /// The global settings: every item's mode for an app without its own.
    #[serde(rename = "sandbox.global")]
    SandboxGlobal,
    /// Change the global settings, and the apps that follow them.
    #[serde(rename = "sandbox.set_global")]
    SandboxSetGlobal { settings: BTreeMap<Item, Mode> },
}

impl Request {
    /// Read a request line. An error is the message to answer it with.
    pub fn parse(line: &str) -> Result<Self, String> {
        serde_json::from_str(line).map_err(|e| {
            // A command this daemon does not have is told apart from a
            // malformed line: the app takes it to mean the module is older
            // than the app.
            match serde_json::from_str::<serde_json::Value>(line) {
                Ok(value) if value.get("cmd").is_some_and(|cmd| !is_known(cmd)) => {
                    "unknown command".to_string()
                }
                _ => format!("malformed request: {e}"),
            }
        })
    }
}

fn is_known(cmd: &serde_json::Value) -> bool {
    matches!(
        cmd.as_str(),
        Some(
            "sandbox.enable"
                | "sandbox.disable"
                | "sandbox.disable_all"
                | "sandbox.list"
                | "sandbox.status"
                | "sandbox.set"
                | "sandbox.global"
                | "sandbox.set_global"
        )
    )
}

#[derive(Serialize)]
struct Answer<T> {
    ok: bool,
    #[serde(flatten)]
    body: T,
}

/// The answer line to a request that succeeded: `{"ok":true,...}`, with the
/// fields of `body` after `ok`.
pub fn success(body: impl Serialize) -> String {
    serde_json::to_string(&Answer { ok: true, body })
        .unwrap_or_else(|e| failure(&format!("cannot encode the answer: {e}")))
}

/// The answer line to a request that failed: `{"ok":false,"error":"..."}`.
pub fn failure(error: &str) -> String {
    #[derive(Serialize)]
    struct Error<'a> {
        error: &'a str,
    }
    serde_json::to_string(&Answer {
        ok: false,
        body: Error { error },
    })
    .expect("a string encodes")
}

/// Listen for commands for `db`. Like the flush socket, only root may
/// connect.
pub fn listen_commands(db: &Path) -> anyhow::Result<UnixListener> {
    let path = command_socket_path(db);
    let _ = fs::remove_file(&path);
    let listener = UnixListener::bind(&path)?;
    fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
    Ok(listener)
}

/// Remove the socket [`listen_commands`] created.
pub fn unlisten_commands(db: &Path) {
    let _ = fs::remove_file(command_socket_path(db));
}

/// Read the request line a client sends, with the connection to answer it
/// on. `None` for a client that sends nothing usable in time: it is
/// answered only if it sent a line, malformed or not.
pub async fn read_request(stream: UnixStream) -> Option<(UnixStream, String)> {
    match tokio::time::timeout(REQUEST_WAIT, read_line(&stream)).await {
        Ok(Ok(line)) => Some((stream, line)),
        Ok(Err(e)) if e.kind() == io::ErrorKind::InvalidData => {
            send_answer(stream, failure(&e.to_string()));
            None
        }
        _ => None,
    }
}

/// Send an answer line in the background: the daemon does not wait for a
/// client to take it, and drops a client that does not take it in time.
pub fn send_answer(stream: UnixStream, mut answer: String) {
    answer.push('\n');
    tokio::spawn(async move {
        let _ = tokio::time::timeout(REQUEST_WAIT, write_all(&stream, answer.as_bytes())).await;
    });
}

/// Read up to the first newline, or the end of what the client sends.
async fn read_line(stream: &UnixStream) -> io::Result<String> {
    let mut line = Vec::new();
    let mut chunk = [0; 512];
    loop {
        stream.readable().await?;
        match stream.try_read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                line.extend_from_slice(&chunk[..n]);
                if let Some(end) = line.iter().position(|&b| b == b'\n') {
                    line.truncate(end);
                    break;
                }
                if line.len() > MAX_REQUEST {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "request too long",
                    ));
                }
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
            Err(e) => return Err(e),
        }
    }
    String::from_utf8(line)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "request is not UTF-8"))
}

async fn write_all(stream: &UnixStream, mut bytes: &[u8]) -> io::Result<()> {
    while !bytes.is_empty() {
        stream.writable().await?;
        match stream.try_write(bytes) {
            Ok(n) => bytes = &bytes[n..],
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// Send `request` to the daemon recording into `db` and return its answer.
/// An answer without `ok` true is an error carrying the daemon's message.
pub fn command(db: &Path, request: &Request) -> anyhow::Result<serde_json::Value> {
    let path = command_socket_path(db);
    let mut stream = match std::os::unix::net::UnixStream::connect(&path) {
        Ok(stream) => stream,
        Err(e) if e.kind() == io::ErrorKind::PermissionDenied => {
            anyhow::bail!("not allowed to reach the daemon; run as root")
        }
        // No socket, or one left by a daemon that has gone.
        Err(_) => anyhow::bail!("the daemon is not running"),
    };
    stream.set_read_timeout(Some(COMMAND_WAIT))?;
    stream.set_write_timeout(Some(COMMAND_WAIT))?;
    let mut line = serde_json::to_string(request)?;
    line.push('\n');
    stream
        .write_all(line.as_bytes())
        .context("sending the command to the daemon")?;
    let mut answer = String::new();
    io::BufReader::new(stream)
        .read_line(&mut answer)
        .map_err(|e| match e.kind() {
            io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut => {
                anyhow::anyhow!("the daemon did not answer in time")
            }
            _ => anyhow::Error::new(e).context("reading the daemon's answer"),
        })?;
    anyhow::ensure!(!answer.is_empty(), "the daemon closed the connection");
    let answer: serde_json::Value = serde_json::from_str(&answer)
        .with_context(|| format!("malformed answer from the daemon: {}", answer.trim_end()))?;
    match answer.get("ok") {
        Some(serde_json::Value::Bool(true)) => Ok(answer),
        _ => anyhow::bail!(
            "{}",
            answer
                .get("error")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("the daemon refused the command")
        ),
    }
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
    fn a_client_that_sends_nothing_still_gets_its_flush_answer() {
        let dir = test_dir("control-old-client");
        let db = dir.join("bb.db");
        let (ready, listening) = std::sync::mpsc::channel();
        let daemon = {
            let db = db.clone();
            std::thread::spawn(move || {
                tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .unwrap()
                    .block_on(async {
                        // Both sockets, as the daemon has them.
                        let flush = listen(&db).unwrap();
                        let _commands = listen_commands(&db).unwrap();
                        ready.send(()).unwrap();
                        let (stream, _) = flush.accept().await.unwrap();
                        answer(&stream);
                        let _ = read_byte(&stream).await;
                    });
            })
        };
        listening.recv().unwrap();
        // An app or query of any version: connect, send nothing, read a byte.
        let mut client = std::os::unix::net::UnixStream::connect(socket_path(&db)).unwrap();
        let mut byte = [0];
        io::Read::read_exact(&mut client, &mut byte).unwrap();
        assert_eq!(byte, [1]);
        drop(client);
        daemon.join().unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    /// Run a command listener on `db` in a thread of its own, answering each
    /// of `connections` requests with `answer(line)`.
    fn serve_commands(
        db: &Path,
        connections: usize,
        answer: fn(&str) -> String,
    ) -> std::thread::JoinHandle<()> {
        let db = db.to_path_buf();
        let (ready, listening) = std::sync::mpsc::channel();
        let thread = std::thread::spawn(move || {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap()
                .block_on(async {
                    let listener = listen_commands(&db).unwrap();
                    ready.send(()).unwrap();
                    for _ in 0..connections {
                        let (stream, _) = listener.accept().await.unwrap();
                        if let Some((stream, line)) = read_request(stream).await {
                            send_answer(stream, answer(&line));
                        }
                    }
                    // Let the answers go out.
                    tokio::time::sleep(Duration::from_millis(100)).await;
                });
        });
        listening.recv().unwrap();
        thread
    }

    #[test]
    fn a_command_gets_the_daemons_answer() {
        let dir = test_dir("control-command");
        let db = dir.join("bb.db");
        let daemon = serve_commands(&db, 2, |line| match Request::parse(line) {
            Ok(Request::SandboxEnable { package, user: 10 }) if package == "com.a" => {
                success(serde_json::json!({"uid": 1_010_100, "also": []}))
            }
            _ => failure("com.b is not installed"),
        });
        let mode = fs::metadata(command_socket_path(&db))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o600);
        let answer = command(
            &db,
            &Request::SandboxEnable {
                package: "com.a".into(),
                user: 10,
            },
        )
        .unwrap();
        assert_eq!(
            answer,
            serde_json::json!({"ok": true, "uid": 1_010_100, "also": []})
        );
        let refused = command(
            &db,
            &Request::SandboxDisable {
                package: "com.b".into(),
                user: 0,
            },
        )
        .unwrap_err();
        assert_eq!(refused.to_string(), "com.b is not installed");
        daemon.join().unwrap();
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_command_fails_at_once_without_a_daemon() {
        let dir = test_dir("control-command-absent");
        let db = dir.join("bb.db");
        let start = std::time::Instant::now();
        let e = command(&db, &Request::SandboxList).unwrap_err();
        assert_eq!(e.to_string(), "the daemon is not running");
        // A socket left behind by a daemon that has gone.
        drop(std::os::unix::net::UnixListener::bind(command_socket_path(&db)).unwrap());
        let e = command(&db, &Request::SandboxList).unwrap_err();
        assert_eq!(e.to_string(), "the daemon is not running");
        assert!(start.elapsed() < Duration::from_secs(1));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn requests_are_read_as_the_protocol_has_them() {
        assert_eq!(
            Request::parse(r#"{"cmd":"sandbox.enable","package":"p","user":0}"#),
            Ok(Request::SandboxEnable {
                package: "p".into(),
                user: 0
            })
        );
        assert_eq!(
            Request::parse(r#"{"cmd":"sandbox.disable","package":"p","user":10}"#),
            Ok(Request::SandboxDisable {
                package: "p".into(),
                user: 10
            })
        );
        assert_eq!(
            Request::parse(r#"{"cmd":"sandbox.list"}"#),
            Ok(Request::SandboxList)
        );
        assert_eq!(
            Request::parse(r#"{"cmd":"sandbox.disable_all"}"#),
            Ok(Request::SandboxDisableAll)
        );
        assert_eq!(
            Request::parse(r#"{"cmd":"sandbox.status"}"#),
            Ok(Request::SandboxStatus)
        );
        assert_eq!(
            Request::parse(r#"{"cmd":"later.thing"}"#),
            Err("unknown command".into())
        );
        assert!(
            Request::parse(r#"{"cmd":"sandbox.enable","user":0}"#)
                .unwrap_err()
                .starts_with("malformed request")
        );
        assert!(Request::parse("").is_err());
        // What the client sends is what the daemon reads.
        let sent = Request::SandboxEnable {
            package: "p".into(),
            user: 3,
        };
        assert_eq!(
            Request::parse(&serde_json::to_string(&sent).unwrap()),
            Ok(sent)
        );
        assert_eq!(failure("x \"y\""), r#"{"ok":false,"error":"x \"y\""}"#);
        assert_eq!(success(()), r#"{"ok":true}"#);
    }

    #[test]
    fn an_oversized_request_is_refused() {
        let dir = test_dir("control-command-long");
        let db = dir.join("bb.db");
        let daemon = serve_commands(&db, 1, |_| unreachable!());
        let mut client = std::os::unix::net::UnixStream::connect(command_socket_path(&db)).unwrap();
        client.write_all(&[b'x'; MAX_REQUEST + 600]).unwrap();
        let mut answer = String::new();
        io::BufReader::new(client).read_line(&mut answer).unwrap();
        assert_eq!(answer, "{\"ok\":false,\"error\":\"request too long\"}\n");
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
