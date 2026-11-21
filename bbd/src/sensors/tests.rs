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

use super::{handles::READ_AGAIN, *};

/// The list of an emulator running Android 15.
pub(super) const ANDROID_15: &str = "Captured at: 15:30:06.721
Sensor Device:
Total 11 h/w sensors, 11 running 0 disabled clients:
0x00000000) active-count = 2; sampling_period(ms) = {200.0, 66.7}, selected = 66.67 ms; batching_period(ms) = {2000.0, 100.0}, selected = 100.00 ms
Sensor List:
0000000000) Goldfish 3-axis Accelerometer | The Android Open Source Project | ver: 1 | type: android.sensor.accelerometer(1) | perm: n/a | flags: 0x00000050
\tcontinuous | minRate=2.00Hz | maxRate=100.00Hz | no batching | non-wakeUp | data-injection, has-additional-info,
0x00000001) Goldfish 3-axis Gyroscope | The Android Open Source Project | ver: 1 | type: android.sensor.gyroscope(4) | perm: n/a | flags: 0x00000050
\tcontinuous | minRate=2.00Hz | maxRate=100.00Hz | no batching | non-wakeUp | data-injection, has-additional-info,
0x00000005) Goldfish Proximity sensor | The Android Open Source Project | ver: 1 | type: android.sensor.proximity(8) | perm: n/a | flags: 0x00000013
\ton-change | maxDelay=0us | minDelay=0us | no batching | wakeUp | data-injection,
0x5f636779) Corrected Gyroscope Sensor | AOSP            | ver: 1 | type: android.sensor.gyroscope(4) | perm: n/a | flags: 0x00000000
\tcontinuous | maxDelay=0us | maxRate=100.00Hz | no batching | non-wakeUp |
Fusion States:
9-axis fusion disabled (0 clients), gyro-rate= 200.00Hz, q=< 0.677207, 2.60163e-06, 2.57113e-05, 0.735793 > (1), b=< 7.43013e-15, 2.06122e-14, 1.13625e-14 >
Recent Sensor events:
Linear Acceleration Sensor: last 10 events
\t 1 (ts=7617.261443600, wall=05:59:36.262) 0.00, 0.00, 0.00,
Active sensors:
Goldfish 3-axis Accelerometer (handle=0x00000000, connections=2)
2 active connections
Connection Number: 0
\tOperating Mode: NORMAL
\t com.android.server.power.FaceDownDetector | WakeLockRefCount 0 | uid 1000 | cache size 0 | max cache size 0
\t Goldfish 3-axis Accelerometer 0x00000000 | status: active | pending flush events 0
Connection Number: 1
\tOperating Mode: NORMAL
\t com.example.a.Listener | WakeLockRefCount 0 | uid 10100 | cache size 0 | max cache size 984
\t Goldfish 3-axis Gyroscope 0x00000001 | status: active | pending flush events 0
0 direct connections
Previous Registrations:
15:19:59 + 0x5f726f76 pid= 8866 uid=10134 package=com.example.a samplingPeriod=20000us batchingPeriod=0us
";

pub(super) fn sensor(kind: &str, continuous: bool) -> Sensor {
    Sensor {
        kind: kind.into(),
        continuous,
    }
}

#[test]
fn subjects_name_the_type_and_mark_a_high_rate() {
    let accelerometer = sensor("android.sensor.accelerometer", true);
    let light = sensor("android.sensor.light", false);
    let base = "binder:android.gui.SensorEventConnection/enableDisable";
    // SENSOR_DELAY_NORMAL, _GAME, 200 Hz, _FASTEST.
    assert_eq!(
        subject(Some(&accelerometer), 200_000_000),
        format!("{base}@android.sensor.accelerometer")
    );
    assert_eq!(
        subject(Some(&accelerometer), 20_000_000),
        format!("{base}@android.sensor.accelerometer")
    );
    assert_eq!(
        subject(Some(&accelerometer), 5_000_000),
        format!("{base}@android.sensor.accelerometer:fast")
    );
    assert_eq!(
        subject(Some(&accelerometer), 0),
        format!("{base}@android.sensor.accelerometer:fast")
    );
    // A sensor that reports on change has no rate to speak of.
    assert_eq!(
        subject(Some(&light), 0),
        format!("{base}@android.sensor.light")
    );
    assert_eq!(subject(None, 0), base);
    // Recorded under sensors, and not taken for a package asked about.
    for subject in [
        format!("{base}@android.sensor.accelerometer:fast"),
        base.to_string(),
    ] {
        assert_eq!(binder::topic_of(&subject), Some(crate::topic::SENSORS));
        assert_eq!(binder::asked_package(&subject), None);
    }
}

// ---- sessions ----

pub(super) const UID: u32 = 10_100;
const PID: u32 = 4242;
const START: u64 = 777;

fn list() -> anyhow::Result<String> {
    Ok(ANDROID_15.to_string())
}

fn failing_list() -> anyhow::Result<String> {
    anyhow::bail!("no dumpsys")
}

/// The same, with the gyroscope's connection that of an isolated process.
fn isolated_list() -> anyhow::Result<String> {
    Ok(ANDROID_15.replace("uid 10100", "uid 99012"))
}

fn call(connection: u32, request: SensorRequest, ts: i64) -> Call {
    Call {
        uid: UID,
        process_uid: UID,
        mode: record::MODE_CALL,
        pid: PID,
        connection,
        request,
        ts,
    }
}

fn on(handle: i32, period_ns: i64) -> SensorRequest {
    SensorRequest::Enable { handle, period_ns }
}

fn running(_pid: u32) -> Option<u64> {
    Some(START)
}

/// (sensor, period, start, end) of each session handed out.
fn spans(settled: &Settled) -> Vec<(String, i64, i64, Option<i64>)> {
    let mut spans: Vec<_> = settled
        .sessions
        .iter()
        .map(|s| (s.sensor.clone(), s.period_ns, s.start_ts, s.end_ts))
        .collect();
    spans.sort();
    spans
}

/// What the table holds after the rows are written in order, by its key.
fn stored(settled: &Settled) -> HashMap<(u32, u32, u32, i32, i64), Option<i64>> {
    settled
        .sessions
        .iter()
        .map(|s| ((s.uid, s.pid, s.connection, s.handle, s.start_ts), s.end_ts))
        .collect()
}

const WAIT: Duration = Duration::from_secs(5);
const GYROSCOPE_FAST: &str =
    "binder:android.gui.SensorEventConnection/enableDisable@android.sensor.gyroscope:fast";

#[test]
fn a_sensor_turned_on_and_off_is_one_session() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    // The list is read for the first sensor; the call waits for it.
    assert_eq!(sensors.on_call(call(9, on(1, 0), 1000), running, now), None);
    let settled = sensors.settle(now, 5000, WAIT);
    assert_eq!(
        settled.calls,
        [(UID, record::MODE_CALL, GYROSCOPE_FAST.to_string(), 1000)]
    );
    // Written while it is on, then again when it ends.
    assert_eq!(
        spans(&settled),
        [("android.sensor.gyroscope".into(), 0, 1000, None)]
    );
    assert!(settled.sessions[0].continuous);
    assert_eq!(sensors.settle(now, 5000, WAIT), Settled::default());
    sensors.on_call(
        call(9, SensorRequest::Disable { handle: 1 }, 1060),
        running,
        now,
    );
    assert_eq!(
        spans(&sensors.settle(now, 5000, WAIT)),
        [("android.sensor.gyroscope".into(), 0, 1000, Some(1060))]
    );
    assert!(!sensors.any_on());
    // Turning off what is not on changes nothing.
    sensors.on_call(
        call(9, SensorRequest::Disable { handle: 1 }, 1070),
        running,
        now,
    );
    assert_eq!(sensors.settle(now, 5000, WAIT), Settled::default());
    // A sensor the list has is recorded at once.
    assert_eq!(
        sensors.on_call(call(9, on(1, 0), 1080), running, now),
        Some((UID, record::MODE_CALL, GYROSCOPE_FAST.to_string(), 1080))
    );
}

#[test]
fn turned_off_and_on_within_a_second_is_stored_as_on() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    sensors.on_call(call(9, on(1, 0), 1000), running, now);
    sensors.on_call(
        call(9, SensorRequest::Disable { handle: 1 }, 1000),
        running,
        now,
    );
    sensors.on_call(call(9, on(1, 0), 1000), running, now);
    let settled = sensors.settle(now, 5000, WAIT);
    // Both rows have the same key; the one written last is on.
    assert_eq!(stored(&settled).into_values().collect::<Vec<_>>(), [None]);
    assert!(sensors.any_on());
}

#[test]
fn closing_a_connection_ends_its_sessions_only() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    for (connection, handle) in [(9, 0), (9, 1), (12, 0)] {
        sensors.on_call(
            call(connection, on(handle, 200_000_000), 1000),
            running,
            now,
        );
    }
    assert_eq!(sensors.settle(now, 5000, WAIT).sessions.len(), 3);
    sensors.on_call(call(9, SensorRequest::Close, 1100), running, now);
    let ended = sensors.settle(now, 5000, WAIT);
    assert_eq!(
        spans(&ended),
        [
            (
                "android.sensor.accelerometer".into(),
                200_000_000,
                1000,
                Some(1100)
            ),
            (
                "android.sensor.gyroscope".into(),
                200_000_000,
                1000,
                Some(1100)
            ),
        ]
    );
    // The other connection's accelerometer is still on.
    assert!(sensors.any_on());
    // A closing call to a connection not known is someone else's.
    sensors.on_call(call(33, SensorRequest::Close, 1200), running, now);
    assert_eq!(sensors.settle(now, 5000, WAIT), Settled::default());
}

#[test]
fn a_new_rate_starts_a_new_session() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    sensors.on_call(call(9, on(0, 200_000_000), 1000), running, now);
    // Within the first second, the rate is updated in place.
    sensors.on_call(call(9, on(0, 20_000_000), 1000), running, now);
    sensors.on_call(call(9, on(0, 20_000_000), 1010), running, now);
    let rate = SensorRequest::Rate {
        handle: 0,
        period_ns: 66_667_000,
    };
    sensors.on_call(call(9, rate, 1030), running, now);
    sensors.on_call(
        call(9, SensorRequest::Disable { handle: 0 }, 1090),
        running,
        now,
    );
    let settled = sensors.settle(now, 5000, WAIT);
    let accelerometer = || "android.sensor.accelerometer".to_string();
    assert_eq!(
        spans(&settled),
        [
            (accelerometer(), 20_000_000, 1000, Some(1030)),
            (accelerometer(), 66_667_000, 1030, Some(1090)),
        ]
    );
    // Each call that turned it on is recorded.
    assert_eq!(settled.calls.len(), 3);
}

#[test]
fn sessions_end_when_their_process_was_last_seen_running() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    sensors.on_call(call(9, on(0, 20_000_000), 1000), running, now);
    // Gone by the time the call is taken in: it was on when it called.
    let other = Call {
        pid: 5555,
        ..call(4, on(0, 20_000_000), 1001)
    };
    sensors.on_call(other, |_| None, now);
    sensors.settle(now, 5000, WAIT);
    let alive = |pid, start| pid == PID && start == START;
    sensors.check_processes(1030, alive);
    assert_eq!(
        spans(&sensors.settle(now, 5000, WAIT)),
        [(
            "android.sensor.accelerometer".into(),
            20_000_000,
            1001,
            Some(1001)
        )]
    );
    // Found running at 1060; found gone after the device slept for
    // seven hours. It ended when it was last seen running, not when
    // that was noticed.
    sensors.check_processes(1060, alive);
    sensors.check_processes(1060 + 7 * 3600, |_, _| false);
    assert_eq!(
        spans(&sensors.settle(now, 5000, WAIT)),
        [(
            "android.sensor.accelerometer".into(),
            20_000_000,
            1000,
            Some(1060)
        )]
    );
    assert!(!sensors.any_on());
}

#[test]
fn an_app_killed_seconds_after_turning_a_sensor_on_keeps_those_seconds() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    sensors.on_call(call(9, on(1, 0), 1000), running, now);
    // Checked every 5 s while it is on; the process dies at 1012.
    for at in [1005, 1010, 1015] {
        sensors.check_processes(at, |_, _| at < 1012);
    }
    assert_eq!(
        spans(&sensors.settle(now, 5000, WAIT)),
        [("android.sensor.gyroscope".into(), 0, 1000, Some(1010))]
    );
}

#[test]
fn a_reused_pid_does_not_take_over_a_session() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    sensors.on_call(call(9, on(0, 20_000_000), 1000), running, now);
    sensors.settle(now, 5000, WAIT);
    // Another process with the same pid, and the same handle for its
    // own connection, turns the sensor off: not the first one's.
    let reused = |_pid| Some(START + 1);
    sensors.on_call(
        call(9, SensorRequest::Disable { handle: 0 }, 2000),
        reused,
        now,
    );
    assert_eq!(
        spans(&sensors.settle(now, 5000, WAIT)),
        [(
            "android.sensor.accelerometer".into(),
            20_000_000,
            1000,
            Some(1000)
        )]
    );
    // Turning it on starts a session of its own.
    sensors.on_call(call(9, on(0, 20_000_000), 2100), reused, now);
    let settled = sensors.settle(now, 5000, WAIT);
    assert_eq!(settled.sessions.len(), 1);
    assert_eq!(
        (settled.sessions[0].proc_start, settled.sessions[0].start_ts),
        (START + 1, 2100)
    );
}

#[test]
fn sessions_left_open_go_on_while_their_process_runs() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    let row = |pid, start_ts| record::SensorSession {
        uid: UID,
        pid,
        proc_start: START,
        connection: 9,
        handle: 1,
        sensor: "android.sensor.gyroscope".into(),
        continuous: true,
        period_ns: 0,
        start_ts,
        end_ts: None,
    };
    // The accelerometer was turned off while no calls were seen: the
    // sensor service has only the gyroscope on for this app.
    let accelerometer = record::SensorSession {
        connection: 12,
        handle: 0,
        sensor: "android.sensor.accelerometer".into(),
        ..row(PID, 1000)
    };
    sensors.adopt(
        vec![
            row(PID, 1000),
            accelerometer,
            row(5555, 1000),
            row(6666, 3000),
        ],
        |pid, _| (pid == PID).then_some(UID),
        2000,
        now,
        4000,
    );
    // Ended when the previous run was last up, and not before they began.
    assert_eq!(
        spans(&sensors.settle(now, 5000, WAIT)),
        [
            ("android.sensor.gyroscope".into(), 0, 1000, Some(2000)),
            ("android.sensor.gyroscope".into(), 0, 3000, Some(3000)),
        ]
    );
    // Once calls are seen again, the service is asked which are still on.
    sensors.watching(now);
    assert_eq!(
        spans(&sensors.settle(now, 5100, WAIT)),
        [("android.sensor.accelerometer".into(), 0, 1000, Some(5100))]
    );
    sensors.on_call(
        call(9, SensorRequest::Disable { handle: 1 }, 6000),
        running,
        now,
    );
    assert_eq!(
        spans(&sensors.settle(now, 6000, WAIT)),
        [("android.sensor.gyroscope".into(), 0, 1000, Some(6000))]
    );
}

#[test]
fn an_isolated_process_is_checked_under_its_own_uid() {
    // Recorded under its app, known to the sensor service by its own.
    let mut sensors = Sensors::new(isolated_list);
    let now = Instant::now();
    let row = record::SensorSession {
        uid: UID,
        pid: PID,
        proc_start: START,
        connection: 9,
        handle: 1,
        sensor: "android.sensor.gyroscope".into(),
        continuous: true,
        period_ns: 0,
        start_ts: 1000,
        end_ts: None,
    };
    sensors.adopt(vec![row], |_, _| Some(99_012), 2000, now, 4000);
    sensors.watching(now);
    assert_eq!(sensors.settle(now, 5000, WAIT), Settled::default());
    assert!(sensors.any_on());
}

#[test]
fn a_handle_the_list_lacks_is_a_call_without_a_type_and_no_session() {
    let mut sensors = Sensors::new(list);
    let now = Instant::now();
    sensors.on_call(call(9, on(99, 0), 1000), running, now);
    let settled = sensors.settle(now, 5000, WAIT);
    let untyped = "binder:android.gui.SensorEventConnection/enableDisable";
    assert_eq!(settled.calls[0].2, untyped);
    // The sensor service turns on no sensor it does not have.
    assert!(settled.sessions.is_empty());
    assert!(!sensors.any_on());
    // Another unknown one soon after waits for the list to be read
    // again, which is not done more than once a minute.
    let later = now + Duration::from_secs(10);
    sensors.on_call(call(9, on(98, 0), 1010), running, later);
    assert!(sensors.settle(later, 5000, WAIT).calls.is_empty());
    let minute = now + READ_AGAIN;
    assert_eq!(sensors.settle(minute, 5000, WAIT).calls.len(), 1);
    // Asked for again much later, a missing handle is not looked up again.
    let read = sensors.list.started;
    let hour = now + Duration::from_secs(3600);
    assert_eq!(
        sensors.on_call(call(9, on(99, 0), 4600), running, hour),
        Some((UID, record::MODE_CALL, untyped.to_string(), 4600))
    );
    assert_eq!(sensors.settle(hour, 5000, WAIT), Settled::default());
    assert_eq!(sensors.list.started, read);
    assert!(!sensors.any_on());
}

#[test]
fn a_list_that_cannot_be_read_does_not_hold_records_back() {
    let mut sensors = Sensors::new(failing_list);
    let now = Instant::now();
    sensors.on_call(call(9, on(0, 0), 1000), running, now);
    let settled = sensors.settle(now, 5000, WAIT);
    assert_eq!(settled.calls.len(), 1);
    assert_eq!(spans(&settled), [(String::new(), 0, 1000, None)]);
    // And it is not tried again at once.
    sensors.on_call(call(9, on(1, 0), 1001), running, now);
    assert_eq!(sensors.settle(now, 5000, WAIT).calls.len(), 1);
    assert!(!sensors.list.is_reading());
}

#[test]
fn one_app_going_through_handles_does_not_crowd_out_others() {
    let mut sensors = Sensors::new(failing_list);
    let now = Instant::now();
    let mut at_once = 0;
    for handle in 0..10_000 {
        at_once += usize::from(
            sensors
                .on_call(call(9, on(handle, 0), 1000), running, now)
                .is_some(),
        );
    }
    // Calls beyond its share are stored at once, without the sensor;
    // sessions beyond it are counted, not kept.
    assert_eq!(sensors.open.len(), MAX_OPEN_PER_APP);
    assert_eq!(sensors.turned_on.len(), MAX_WAITING_PER_APP);
    assert_eq!(at_once, 10_000 - MAX_WAITING_PER_APP);
    assert_eq!(sensors.dropped(), (10_000 - MAX_OPEN_PER_APP) as u64);
    // Another app's sensor still has its session and its call.
    let other = Call {
        uid: 10_200,
        process_uid: 10_200,
        pid: 5555,
        ..call(3, on(0, 0), 1000)
    };
    sensors.on_call(other, running, now);
    assert_eq!(sensors.open.len(), MAX_OPEN_PER_APP + 1);
    let settled = sensors.settle(now, 5000, WAIT);
    assert_eq!(settled.calls.len(), MAX_WAITING_PER_APP + 1);
    assert!(sensors.waiting_per_app.is_empty());
    // Ending the sessions frees the app's share.
    sensors.on_call(call(9, SensorRequest::Close, 1100), running, now);
    assert_eq!(sensors.open_per_app.get(&UID), None);
    assert_eq!(sensors.open_per_app.get(&10_200), Some(&1));
}
