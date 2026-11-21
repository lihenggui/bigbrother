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

//! Shared models and conventions for recorded access.

/// Access mode bits, as stored in `access.mode`.
pub type Mode = u16;

pub const MODE_READ: Mode = 1;
pub const MODE_WRITE: Mode = 2;
pub const MODE_DIR: Mode = 4;
/// stat/access/readlink: the app learned the path exists and its metadata.
pub const MODE_PROBE: Mode = 8;
/// inotify: the app asked to be told when the path changes.
pub const MODE_WATCH: Mode = 16;
/// The access went through a system content provider (MediaStore, the
/// Storage Access Framework, downloads) rather than the app opening the file
/// itself. Combined with the read/write bits for opens.
pub const MODE_VIA_PROVIDER: Mode = 32;
/// A content provider query: the app listed entries without opening them.
pub const MODE_QUERY: Mode = 64;
/// A connection or datagram to a network address.
pub const MODE_CONNECT: Mode = 128;
/// A program the app started.
pub const MODE_EXEC: Mode = 256;
/// A request to a system service.
pub const MODE_CALL: Mode = 512;
/// Done by one of the app's isolated processes (a sandboxed renderer or the
/// like), attributed to the app. Combined with the other bits.
pub const MODE_ISOLATED: Mode = 1024;

/// What kind of information a recorded path exposes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, clap::ValueEnum)]
pub enum Category {
    /// User files on shared storage.
    Storage,
    /// Hardware and device identifiers (/sys, selected /dev nodes).
    Device,
    /// System-wide kernel information (/proc/cpuinfo, /proc/net, ...).
    System,
    /// Another app's or the system's processes (/proc/<pid>).
    Process,
    /// Content provider collections the app queried (the media library,
    /// document trees, downloads), identified by URI rather than file path.
    Provider,
    /// Addresses on this device and the local network the app contacted,
    /// identified as `net://<address>:<port>`.
    Network,
    /// Programs the app started, identified as `exec:<command line>`.
    Command,
    /// Personal data kept by system content providers (contacts, messages,
    /// the call log, the calendar), identified by the collection's URI.
    Personal,
    /// What the app asked system services for (installed apps, clipboard,
    /// location...), identified as `binder:<interface>/<method>`.
    Service,
    /// Uses of sensitive permissions as the system recorded them (camera,
    /// microphone, location...), identified as `appop:<op name>`.
    Permission,
}

impl Category {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Storage => "storage",
            Self::Device => "device",
            Self::System => "system",
            Self::Process => "process",
            Self::Provider => "provider",
            Self::Network => "network",
            Self::Command => "command",
            Self::Personal => "personal",
            Self::Service => "service",
            Self::Permission => "permission",
        }
    }
}

/// One period an app had a sensor on: a `sensor_session` row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SensorSession {
    pub uid: u32,
    pub pid: u32,
    pub proc_start: u64,
    pub connection: u32,
    pub handle: i32,
    pub sensor: String,
    pub continuous: bool,
    pub period_ns: i64,
    pub start_ts: i64,
    pub end_ts: Option<i64>,
}

/// Sampling periods at or below this are a high rate: 5 ms, 200 Hz. It is
/// the rate Android 12 and later hold apps to unless they have the
/// HIGH_SAMPLING_RATE_SENSORS permission, since motion sampled faster can
/// pick up speech from the device's own speaker and what is typed on its
/// screen. Asking for the fastest rate the sensor has (a period of 0, which
/// `SENSOR_DELAY_FASTEST` sends) is a high rate too; `SENSOR_DELAY_GAME`
/// (20 ms, 50 Hz) is not.
pub const HIGH_RATE_PERIOD_NS: i64 = 5_000_000;

/// Whether a sampling period asked of a sensor is a high rate. Only
/// sensors that report at a steady rate have one; the others report on
/// change or once, whatever period they are given.
pub fn is_high_rate(continuous: bool, period_ns: i64) -> bool {
    continuous && period_ns <= HIGH_RATE_PERIOD_NS
}
