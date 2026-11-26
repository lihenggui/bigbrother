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

use super::*;
use std::net::Ipv4Addr;

#[test]
fn serves_a_recording_app_without_name_rules() {
    use std::collections::BTreeMap;

    use crate::sandbox::tests::Fixture;
    use crate::sandbox_item::{Item, Mode};

    let mut f = Fixture::new("recording-resolver");
    let mut sandbox = f.sandbox();
    sandbox
        .enable(&f.writer, &mut f.packages, "com.app", 0, 1000)
        .unwrap();
    let (serves, refuses) = sandbox.name_policy();
    assert!(serves(10100).is_none());

    sandbox
        .configure(
            &f.writer,
            &mut f.packages,
            "com.app",
            0,
            &BTreeMap::from([(Item::NetworkRecord, Some(Mode::On))]),
        )
        .unwrap();
    assert_eq!(serves(10100).as_deref(), Some("com.app"));
    assert!(!refuses(10100, Some("example.com")));
    assert!(serves(10200).is_none());
}

#[tokio::test]
async fn a_listener_that_exits_reports_it_is_no_longer_serving() {
    let serving = Serving::default();
    assert!(!serving.alive());
    let stopped = Arc::new(Notify::new());
    let serving = Serving::listening((1, 2));
    let listener = ListenerLifetime {
        serving: serving.clone(),
        stopped: stopped.clone(),
    };
    assert!(serving.alive());
    drop(listener);
    assert!(!serving.alive());
    tokio::time::timeout(Duration::ZERO, stopped.notified())
        .await
        .unwrap();
}

#[test]
fn an_app_has_a_few_lookups_at_once_and_all_apps_some_more() {
    let (refused, _) = RefusedLookups::channel();
    let shared = Shared {
        names: Arc::default(),
        serves: Arc::new(|_| Some(Arc::from("installation"))),
        refuses: Arc::new(|_, _| false),
        refused,
        at_once: AtomicUsize::new(0),
        of_each: Mutex::default(),
    };
    for _ in 0..MOST_OF_ONE_APP {
        assert!(shared.take_place(10001));
    }
    // One more of the same app has none; another app has.
    assert!(!shared.take_place(10001));
    assert!(shared.take_place(10002));
    shared.leave_place(10001);
    assert!(shared.take_place(10001));
    // All apps together.
    let mut uid = 20000;
    while shared.at_once.load(Ordering::Relaxed) < MOST_AT_ONCE {
        uid += 1;
        assert!(shared.take_place(uid));
    }
    assert!(!shared.take_place(30000));
    assert!(!shared.of_each.lock().unwrap().contains_key(&30000));
    shared.leave_place(uid);
    assert!(shared.take_place(30000));
    // Every place left is a place again.
    let uids: Vec<u32> = shared.of_each.lock().unwrap().keys().copied().collect();
    for uid in uids {
        loop {
            let has = shared.of_each.lock().unwrap().contains_key(&uid);
            if !has {
                break;
            }
            shared.leave_place(uid);
        }
    }
    assert_eq!(shared.at_once.load(Ordering::Relaxed), 0);
}

#[test]
fn a_full_refusal_queue_drops_the_record_and_still_refuses_the_lookup() {
    let (refused, pending) = RefusedLookups::channel();
    for _ in 0..REFUSED_LOOKUPS_CAPACITY {
        refused.record(10001, "queued.example");
    }
    let shared = Shared {
        names: Arc::default(),
        serves: Arc::new(|_| Some(Arc::from("installation"))),
        refuses: Arc::new(|_, host| host == Some("blocked.example")),
        refused,
        at_once: AtomicUsize::new(0),
        of_each: Mutex::default(),
    };
    let (mut client, server) = UnixStream::pair().unwrap();
    client
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    client
        .write_all(b"getaddrinfo blocked.example ^ 0 0 0 0 0\0")
        .unwrap();

    std::thread::scope(|scope| {
        let task = scope.spawn(move || {
            relay(
                server,
                10002,
                Arc::from("installation"),
                Instant::now() + REQUEST_WAIT,
                &shared,
            )
        });
        let Ok(closed) = client.read(&mut [0u8]) else {
            drop(pending);
            panic!("the refusal waited for space in the queue");
        };
        task.join().unwrap().unwrap();
        assert_eq!(closed, 0);
        assert_eq!(pending.dropped(), 1);
        let queued: Vec<_> = pending.batch().collect();
        assert_eq!(queued.len(), REFUSED_LOOKUPS_CAPACITY);
        assert_eq!(pending.batch().count(), 0);
        assert!(
            queued
                .iter()
                .all(|(uid, host)| { *uid == 10001 && host == "queued.example" })
        );
    });
}

#[test]
fn a_batch_ends_even_when_new_refusals_keep_arriving() {
    let (refused, pending) = RefusedLookups::channel();
    for _ in 0..REFUSED_LOOKUPS_CAPACITY {
        refused.record(10001, "queued.example");
    }
    let drained = pending
        .batch()
        .take(REFUSED_LOOKUPS_CAPACITY + 1)
        .inspect(|_| refused.record(10002, "arriving.example"))
        .count();
    assert_eq!(drained, REFUSED_LOOKUPS_CAPACITY);
    assert_eq!(pending.dropped(), 0);
    let queued: Vec<_> = pending.batch().collect();
    assert_eq!(queued.len(), REFUSED_LOOKUPS_CAPACITY);
    assert!(
        queued
            .iter()
            .all(|(uid, host)| { *uid == 10002 && host == "arriving.example" })
    );
}

#[test]
fn only_one_command_is_taken_on_a_connection() {
    assert!(one_command(b"getaddrinfo example.com ^ 0 0 0 0 0\0"));
    // A second one behind it, whole or begun, and what does not end.
    assert!(!one_command(
        b"getaddrinfo example.com ^ 0 0 0 0 0\0getaddrinfo t.adnet.example ^ 0 0 0 0 0\0"
    ));
    assert!(!one_command(b"getdnsnetid 0\0g"));
    assert!(!one_command(b"getdnsnetid 0"));
    assert!(!one_command(b""));
    assert!(one_command(b"\0"));
}

#[test]
fn the_name_an_address_was_last_given_for_is_remembered_per_app() {
    let names = Names::default();
    let (a, b): (IpAddr, IpAddr) = (
        "203.0.113.7".parse().unwrap(),
        "2001:db8::5".parse().unwrap(),
    );
    names.learn(10001, Arc::from("installation"), "api.example.com", &[a, b]);
    names.learn(10002, Arc::from("installation"), "cdn.example.net", &[a]);
    assert_eq!(names.host_of(10001, a).as_deref(), Some("api.example.com"));
    assert_eq!(names.host_of(10001, b).as_deref(), Some("api.example.com"));
    assert_eq!(names.host_of(10002, a).as_deref(), Some("cdn.example.net"));
    assert_eq!(names.host_of(10002, b), None);
    // An address given for another name since is that name's.
    names.learn(10001, Arc::from("installation"), "img.example.com", &[a]);
    assert_eq!(names.host_of(10001, a).as_deref(), Some("img.example.com"));
    // An IPv4 address is the same however it is written.
    assert_eq!(
        names
            .host_of(10001, "::ffff:203.0.113.7".parse().unwrap())
            .as_deref(),
        Some("img.example.com")
    );
    let mut under: Vec<IpAddr> = names
        .addresses_of(10001, "installation", crate::clock::now(), |host| {
            host.ends_with(".example.com")
        })
        .into_iter()
        .map(|(ip, _)| ip)
        .collect();
    under.sort();
    assert_eq!(under, [a, b]);
    assert!(
        names
            .addresses_of(10003, "installation", crate::clock::now(), |_| true)
            .is_empty()
    );
}

#[test]
fn a_late_answer_from_a_previous_installation_cannot_supply_rule_addresses() {
    let names = Names::default();
    let ip = "203.0.113.7".parse().unwrap();
    names.learn(10001, Arc::from("previous"), "example.test", &[ip]);
    assert!(
        names
            .addresses_of(10001, "current", crate::clock::now(), |_| true)
            .is_empty()
    );
    names.learn(10001, Arc::from("current"), "example.test", &[ip]);
    assert_eq!(
        names.addresses_of(10001, "current", crate::clock::now(), |_| true)[0].0,
        ip
    );
}

#[test]
fn the_oldest_half_goes_when_too_much_is_remembered() {
    let names = Names::default();
    for i in 0..=REMEMBERED as u32 {
        names.learn(
            10001,
            Arc::from("installation"),
            "h.example.com",
            &[IpAddr::V4(Ipv4Addr::from(i))],
        );
    }
    let kept = names
        .addresses_of(10001, "installation", crate::clock::now(), |_| true)
        .len();
    assert_eq!(kept, REMEMBERED / 2);
    assert_eq!(names.host_of(10001, IpAddr::V4(Ipv4Addr::from(0))), None);
    assert!(
        names
            .host_of(10001, IpAddr::V4(Ipv4Addr::from(REMEMBERED as u32)))
            .is_some()
    );
}

#[test]
fn an_app_that_looks_up_many_names_keeps_those_of_the_others() {
    let names = Names::default();
    let other = IpAddr::V4(Ipv4Addr::new(203, 0, 113, 9));
    names.learn(
        10002,
        Arc::from("installation"),
        "other.example.com",
        &[other],
    );
    for i in 0..=2 * REMEMBERED as u32 {
        names.learn(
            10001,
            Arc::from("installation"),
            "h.example.com",
            &[IpAddr::V4(Ipv4Addr::from(i))],
        );
    }
    assert_eq!(
        names.host_of(10002, other).as_deref(),
        Some("other.example.com")
    );
}

#[test]
fn nothing_is_remembered_of_a_uid_that_went_to_another_app() {
    let names = Names::default();
    let address = IpAddr::V4(Ipv4Addr::new(203, 0, 113, 9));
    names.learn(
        10001,
        Arc::from("installation"),
        "h.example.com",
        &[address],
    );
    names.keep_refused(10001, BTreeSet::from([address]));
    assert!(names.refused(10001, address));
    names.forget(10001);
    assert_eq!(names.host_of(10001, address), None);
    assert!(!names.refused(10001, address));
}

fn listener_at(name: &str) -> (PathBuf, Arc<UnixListener>, Serving) {
    let path = std::env::temp_dir().join(format!("bbd-{name}-{}.sock", std::process::id()));
    let listener = Arc::new(UnixListener::bind(&path).unwrap());
    let serving = Serving::default();
    serving.set_listener(listener.clone(), &path).unwrap();
    (path, listener, serving)
}

fn accepted_jobs(
    listener: Arc<UnixListener>,
    serving: Serving,
    shared: Arc<Shared>,
    dispatch: impl FnMut(Lookup) -> io::Result<()> + Send + 'static,
) -> Receiver<()> {
    let (exited, done) = sync_channel(1);
    std::thread::spawn(move || {
        accept_lookups(&listener, &serving, &shared, dispatch);
        exited.send(()).unwrap();
    });
    done
}

fn test_shared() -> Arc<Shared> {
    let (refused, _) = RefusedLookups::channel();
    Arc::new(Shared {
        names: Arc::default(),
        serves: Arc::new(|_| Some(Arc::from("installation"))),
        refuses: Arc::new(|_, _| false),
        refused,
        at_once: AtomicUsize::new(0),
        of_each: Mutex::default(),
    })
}

fn test_job(client: UnixStream) -> Lookup {
    Lookup {
        client,
        uid: 10001,
        install_id: Arc::from("installation"),
        asked_by: Instant::now() + REQUEST_WAIT,
    }
}

#[test]
fn a_lookup_pool_starts_empty_and_reuses_a_worker_across_bursts() {
    let (handled, done) = sync_channel(2);
    let pool = LookupPool::new(1, 2, LOOKUP_IDLE, move |_| {
        handled.send(std::thread::current().id()).unwrap();
    });
    assert_eq!(lock(&pool.core.state).workers, 0);
    let (client, _peer) = UnixStream::pair().unwrap();
    let mut first = None;
    for _ in 0..3 {
        for _ in 0..2 {
            pool.dispatch(test_job(client.try_clone().unwrap()))
                .unwrap();
        }
        for _ in 0..2 {
            let worker = done.recv_timeout(REQUEST_WAIT).unwrap();
            assert_eq!(*first.get_or_insert(worker), worker);
        }
        assert_eq!(lock(&pool.core.state).workers, 1);
    }
    drop(pool);
    assert_eq!(
        done.recv_timeout(REQUEST_WAIT),
        Err(std::sync::mpsc::RecvTimeoutError::Disconnected)
    );
}

#[test]
fn an_idle_worker_retires_and_a_later_lookup_starts_another() {
    let (handled, done) = sync_channel(1);
    let (retired, exited) = sync_channel(1);
    let pool = LookupPool::new(1, 1, Duration::from_millis(20), move |_| {
        handled.send(std::thread::current().id()).unwrap();
    });
    let spawn = |workers: Arc<LookupWorkers>| {
        let retired = retired.clone();
        std::thread::Builder::new().spawn(move || {
            workers.run();
            retired.send(()).unwrap();
        })?;
        Ok(())
    };
    let (client, _peer) = UnixStream::pair().unwrap();
    let mut previous = None;
    for _ in 0..2 {
        pool.dispatch_with(test_job(client.try_clone().unwrap()), spawn)
            .unwrap();
        let worker = done.recv_timeout(REQUEST_WAIT).unwrap();
        assert_ne!(previous.replace(worker), Some(worker));
        exited.recv_timeout(REQUEST_WAIT).unwrap();
        assert_eq!(lock(&pool.core.state).workers, 0);
    }
}

#[test]
fn a_failed_spawn_leaves_the_lookup_queued_for_an_existing_worker() {
    let (handled, done) = sync_channel(2);
    let pool = LookupPool::new(2, 1, LOOKUP_IDLE, move |mut job| {
        handled.send(std::thread::current().id()).unwrap();
        job.client.set_read_timeout(Some(REQUEST_WAIT)).unwrap();
        job.client.read_exact(&mut [0]).unwrap();
    });
    let (first, mut first_peer) = UnixStream::pair().unwrap();
    pool.dispatch(test_job(first)).unwrap();
    let worker = done.recv_timeout(REQUEST_WAIT).unwrap();
    let (second, mut second_peer) = UnixStream::pair().unwrap();
    let mut attempted = false;
    pool.dispatch_with(test_job(second), |_| {
        attempted = true;
        Err(io::Error::other("injected spawn failure"))
    })
    .unwrap();
    assert!(attempted);
    assert_eq!(lock(&pool.core.state).pending.len(), 1);
    let (third, _third_peer) = UnixStream::pair().unwrap();
    assert_eq!(
        pool.dispatch(test_job(third)).unwrap_err().kind(),
        io::ErrorKind::WouldBlock
    );
    first_peer.write_all(&[1]).unwrap();
    assert_eq!(done.recv_timeout(REQUEST_WAIT).unwrap(), worker);
    second_peer.write_all(&[1]).unwrap();
}

#[test]
fn a_failed_first_spawn_releases_the_slot_and_accepts_the_next_lookup() {
    let (path, listener, serving) = listener_at("dns-spawn");
    let shared = test_shared();
    let (handled, jobs) = sync_channel(1);
    let pool = LookupPool::new(1, 1, LOOKUP_IDLE, move |job| handled.send(job).unwrap());
    let mut first = true;
    let done = accepted_jobs(listener, serving.clone(), shared.clone(), move |job| {
        if std::mem::take(&mut first) {
            pool.dispatch_with(job, |_| Err(io::Error::other("injected spawn failure")))
        } else {
            pool.dispatch(job)
        }
    });

    let mut rejected = UnixStream::connect(&path).unwrap();
    rejected.set_read_timeout(Some(REQUEST_WAIT)).unwrap();
    assert_eq!(rejected.read(&mut [0]).unwrap(), 0);
    let _next = UnixStream::connect(&path).unwrap();
    let job = jobs.recv_timeout(REQUEST_WAIT).unwrap();
    assert_eq!(shared.at_once.load(Ordering::Relaxed), 1);
    shared.leave_place(job.uid);
    assert_eq!(shared.at_once.load(Ordering::Relaxed), 0);
    assert!(serving.stop());
    done.recv_timeout(REQUEST_WAIT).unwrap();
}

#[test]
fn a_full_worker_queue_releases_the_slot_and_accepts_the_next_lookup() {
    let (path, listener, serving) = listener_at("dns-queue");
    let shared = test_shared();
    let (pending, jobs) = sync_channel(1);
    let (client, _peer) = UnixStream::pair().unwrap();
    pending.try_send(test_job(client)).unwrap();
    let done = accepted_jobs(listener, serving.clone(), shared.clone(), move |job| {
        pending.try_send(job).map_err(io::Error::other)
    });

    let mut rejected = UnixStream::connect(&path).unwrap();
    rejected.set_read_timeout(Some(REQUEST_WAIT)).unwrap();
    assert_eq!(rejected.read(&mut [0]).unwrap(), 0);
    // Closing the peer can race the slot release, so verify after dispatch
    // has advanced to the next connection.
    jobs.try_recv().unwrap();
    let _next = UnixStream::connect(&path).unwrap();
    let job = jobs.recv_timeout(REQUEST_WAIT).unwrap();
    assert_eq!(shared.at_once.load(Ordering::Relaxed), 1);
    shared.leave_place(job.uid);
    assert_eq!(shared.at_once.load(Ordering::Relaxed), 0);
    assert!(serving.stop());
    done.recv_timeout(REQUEST_WAIT).unwrap();
}

#[test]
fn stopping_a_live_relay_wakes_accept_and_unlinks_its_socket() {
    let (path, listener, serving) = listener_at("dns-stop");
    let shared = test_shared();
    let (pending, jobs) = sync_channel(1);
    let done = accepted_jobs(listener, serving.clone(), shared.clone(), move |job| {
        pending.try_send(job).map_err(io::Error::other)
    });
    let _client = UnixStream::connect(&path).unwrap();
    let job = jobs.recv_timeout(REQUEST_WAIT).unwrap();
    shared.leave_place(job.uid);
    assert!(serving.stop());
    done.recv_timeout(REQUEST_WAIT).unwrap();
    assert!(!serving.alive());
    assert!(!path.exists());
    assert!(!serving.stop());
    assert_eq!(
        UnixStream::connect(&path).unwrap_err().kind(),
        io::ErrorKind::NotFound
    );
}
