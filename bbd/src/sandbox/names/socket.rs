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

use std::{
    ffi::CString,
    io,
    os::{
        fd::{AsRawFd as _, FromRawFd as _, OwnedFd},
        unix::{
            ffi::OsStrExt as _,
            fs::PermissionsExt as _,
            net::{UnixListener, UnixStream},
        },
    },
    path::Path,
};

use anyhow::Context as _;

use super::{NETD, RELAY_SOCKET};

/// The label of netd's processes, and of the file of its socket.
const NETD_LABEL: &str = "u:r:netd:s0";
const SOCKET_LABEL: &str = "u:object_r:dnsproxyd_socket:s0";

/// The group that may connect to netd's socket, which apps with the
/// network permission are in.
const INET_GID: libc::gid_t = 3003;

/// The daemon's socket, labelled as netd's.
pub(super) fn listen() -> anyhow::Result<UnixListener> {
    match std::fs::remove_file(RELAY_SOCKET) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => {
            return Err(e).context("removing the socket of the daemon before");
        }
        _ => {}
    }
    const SOCKCREATE: &str = "/proc/thread-self/attr/sockcreate";
    std::fs::write(SOCKCREATE, NETD_LABEL).context("labelling the socket")?;
    let bound = UnixListener::bind(RELAY_SOCKET);
    let reset = std::fs::OpenOptions::new()
        .write(true)
        .open(SOCKCREATE)
        .and_then(|file| {
            // SELinux needs the zero-byte syscall to clear the override
            // before any workers inherit it. write_all would skip it.
            // SAFETY: the descriptor is open and 0 bytes are read.
            let written = unsafe { libc::write(file.as_raw_fd(), std::ptr::null(), 0) };
            if written < 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        })
        .context("clearing the socket label override");
    let listener = bound.context("binding the socket")?;
    if let Err(error) = reset.and_then(|()| label_bound_socket()) {
        remove_relay_socket(Path::new(RELAY_SOCKET));
        return Err(error);
    }
    Ok(listener)
}

pub(super) fn remove_relay_socket(path: &Path) {
    if let Err(e) = std::fs::remove_file(path)
        && e.kind() != io::ErrorKind::NotFound
    {
        eprintln!("bbd: cannot remove the resolver socket: {e}");
    }
}

fn label_bound_socket() -> anyhow::Result<()> {
    // To whom netd's own is open: root, and the group of the apps with
    // the network permission.
    std::os::unix::fs::chown(RELAY_SOCKET, Some(0), Some(INET_GID))
        .and_then(|()| {
            std::fs::set_permissions(RELAY_SOCKET, std::fs::Permissions::from_mode(0o660))
        })
        .context("opening the socket to the apps with the network permission")?;
    let (path, name, label) = (
        CString::new(RELAY_SOCKET)?,
        c"security.selinux",
        CString::new(SOCKET_LABEL)?,
    );
    let rc = unsafe {
        libc::setxattr(
            path.as_ptr(),
            name.as_ptr(),
            label.as_ptr().cast(),
            label.as_bytes_with_nul().len(),
            0,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error()).context("labelling the socket's file");
    }
    Ok(())
}

/// The uid of the process at the other end, which the kernel tells.
pub(super) fn peer_uid(client: &UnixStream) -> io::Result<u32> {
    let mut cred = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            client.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&raw mut cred).cast(),
            &mut len,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(cred.uid)
}

/// Connect to netd with `uid` as this thread's effective uid: netd takes
/// the app to be whoever connects. The thread stays root otherwise, and
/// is root again once connected.
pub(super) fn connect_as(uid: u32) -> io::Result<UnixStream> {
    // Raw system calls: these change the calling thread only, where the
    // C library's would change every thread of the daemon.
    let set = |euid: libc::uid_t| -> io::Result<()> {
        let keep = libc::uid_t::MAX;
        if unsafe { libc::syscall(libc::SYS_setresuid, keep, euid, keep) } != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    };
    // Without root's capabilities the socket is open to its group only.
    let groups = [INET_GID];
    if unsafe { libc::syscall(libc::SYS_setgroups, groups.len(), groups.as_ptr()) } != 0 {
        return Err(io::Error::last_os_error());
    }
    let connected = set(uid).and_then(|()| connect_without_waiting(Path::new(NETD)));
    // A reusable worker must leave every lookup with root's credentials,
    // including when switching to the app or connecting failed.
    if set(0).is_err()
        || unsafe { libc::syscall(libc::SYS_setgroups, 0usize, std::ptr::null::<libc::gid_t>()) }
            != 0
    {
        std::process::abort();
    }
    let stream = connected?;
    stream.set_nonblocking(false)?;
    Ok(stream)
}

fn connect_without_waiting(path: &Path) -> io::Result<UnixStream> {
    let path = CString::new(path.as_os_str().as_bytes())?;
    // SAFETY: an all-zero sockaddr_un is valid before the family and path are filled.
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    let bytes = path.as_bytes_with_nul();
    if bytes.len() > address.sun_path.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "socket path too long",
        ));
    }
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    for (dst, src) in address.sun_path.iter_mut().zip(bytes) {
        *dst = *src as libc::c_char;
    }
    // A full netd listen queue must not tie up a reusable worker.
    let fd = unsafe {
        libc::socket(
            libc::AF_UNIX,
            libc::SOCK_STREAM | libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
            0,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: socket returned a new descriptor.
    let stream = UnixStream::from(unsafe { OwnedFd::from_raw_fd(fd) });
    // SAFETY: the descriptor is open and address is an initialized sockaddr_un.
    let rc = unsafe {
        libc::connect(
            stream.as_raw_fd(),
            (&raw const address).cast(),
            size_of::<libc::sockaddr_un>() as libc::socklen_t,
        )
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(stream)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_full_upstream_listen_queue_returns_control_to_the_worker() {
        let path =
            std::env::temp_dir().join(format!("bbd-dns-upstream-{}.sock", std::process::id()));
        let listener = UnixListener::bind(&path).unwrap();
        // A Linux Unix listener with a zero backlog holds one connection.
        // SAFETY: listener owns the descriptor.
        assert_eq!(unsafe { libc::listen(listener.as_raw_fd(), 0) }, 0);
        let _first = connect_without_waiting(&path).unwrap();
        assert_eq!(
            connect_without_waiting(&path).unwrap_err().kind(),
            io::ErrorKind::WouldBlock
        );
        listener.accept().unwrap();
        connect_without_waiting(&path).unwrap();
        std::fs::remove_file(path).unwrap();
    }
}
