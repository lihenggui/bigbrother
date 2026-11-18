-- Copyright (C) 2026 Mercury Li
--
-- This program is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License version 3 as
-- published by the Free Software Foundation.
--
-- This program is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
-- GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License
-- along with this program. If not, see <https://www.gnu.org/licenses/>.

-- A database as v0.2.0 (schema version 3) wrote it. The statements up to
-- the version are that release's SCHEMA and EVENT_SCHEMA in
-- bbd/src/store.rs, unchanged, as it created a new database. The rows are
-- made up, with one of every kind of record and setting v0.2.0 wrote.

CREATE TABLE IF NOT EXISTS access (
    hour     INTEGER NOT NULL,   -- unix time / 3600
    uid      INTEGER NOT NULL,
    pkg      TEXT    NOT NULL,   -- package name at record time ('' if unknown)
    cat      TEXT    NOT NULL,   -- Category::as_str()
    path     TEXT    NOT NULL,   -- what was accessed: a canonical path, a content
                                 -- URI, 'net://...' or 'exec:...', by category
    mode     INTEGER NOT NULL,   -- MODE_* bits
    ok       INTEGER NOT NULL,   -- opens that succeeded
    fail     INTEGER NOT NULL,   -- opens that failed (denied, missing...)
    first_ts INTEGER NOT NULL,
    last_ts  INTEGER NOT NULL,
    items    INTEGER NOT NULL DEFAULT 0,  -- most entries one provider query returned
    PRIMARY KEY (hour, uid, path, mode)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS access_uid_last ON access (uid, last_ts);
CREATE INDEX IF NOT EXISTS access_last ON access (last_ts);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS path (
    id   INTEGER PRIMARY KEY,
    path TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS event (
    uid     INTEGER NOT NULL,
    path_id INTEGER NOT NULL REFERENCES path (id),
    mode    INTEGER NOT NULL,   -- MODE_* bits
    ts      INTEGER NOT NULL,   -- unix time
    ok      INTEGER NOT NULL,
    fail    INTEGER NOT NULL,
    PRIMARY KEY (uid, path_id, mode, ts)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS event_ts ON event (ts);
PRAGMA user_version = 3;

-- Hour 488890 starts at 1760004000.
INSERT INTO access VALUES
    (488890, 10123, 'com.example.gallery', 'storage', '/storage/emulated/0/DCIM/Camera/IMG_0001.jpg', 1, 3, 0, 1760004010, 1760004050, 0),
    (488891, 10123, 'com.example.gallery', 'storage', '/storage/emulated/0/DCIM/Camera/IMG_0001.jpg', 1, 1, 1, 1760007700, 1760007705, 0),
    (488890, 10123, 'com.example.gallery', 'storage', '/storage/emulated/0/Documents/O''Brien résumé.pdf', 33, 1, 0, 1760004100, 1760004100, 0),
    (488890, 10123, 'com.example.gallery', 'provider', 'content://media/external/images/media', 64, 2, 0, 1760004000, 1760004020, 245),
    (488890, 10124, 'com.example.tracker', 'device', '/sys/class/net/wlan0/address', 1, 2, 0, 1760004200, 1760004260, 0),
    (488890, 10124, 'com.example.tracker', 'system', '/proc/cpuinfo', 1025, 1, 0, 1760004300, 1760004300, 0),
    (488890, 10124, 'com.example.tracker', 'process', '/proc/1234/cmdline', 8, 0, 4, 1760004310, 1760004320, 0),
    (488890, 10124, 'com.example.tracker', 'network', 'net://192.168.1.1:53', 128, 5, 0, 1760004400, 1760004450, 0),
    (488890, 10124, 'com.example.tracker', 'command', 'exec:getprop ro.serialno', 256, 1, 0, 1760004500, 1760004500, 0),
    (488890, 10124, 'com.example.tracker', 'personal', 'content://com.android.contacts/contacts', 64, 1, 0, 1760004600, 1760004600, 120),
    (488890, 10124, 'com.example.tracker', 'service', 'binder:android.content.pm.IPackageManager/getInstalledPackages', 512, 2, 0, 1760004700, 1760004710, 0),
    (488890, 10124, 'com.example.tracker', 'permission', 'appop:android:coarse_location', 1, 1, 1, 1760004800, 1760004900, 0),
    (488890, 10200, '', 'storage', '/storage/emulated/0/Download', 20, 1, 0, 1760005000, 1760005000, 0),
    (488890, 1010123, 'com.example.gallery', 'storage', '/storage/emulated/10/Pictures/a.png', 2, 1, 0, 1760005100, 1760005100, 0);

INSERT INTO path VALUES
    (1, '/storage/emulated/0/DCIM/Camera/IMG_0001.jpg'),
    (2, '/storage/emulated/0/Documents/O''Brien résumé.pdf'),
    (3, 'content://media/external/images/media'),
    (4, '/sys/class/net/wlan0/address'),
    (5, 'net://192.168.1.1:53'),
    (6, 'appop:android:coarse_location'),
    (7, '/storage/emulated/10/Pictures/a.png');

INSERT INTO event VALUES
    (10123, 1, 1, 1760004010, 2, 0),
    (10123, 1, 1, 1760004050, 1, 0),
    (10123, 1, 1, 1760007700, 1, 0),
    (10123, 1, 1, 1760007705, 0, 1),
    (10123, 2, 33, 1760004100, 1, 0),
    (10123, 3, 64, 1760004000, 1, 0),
    (10123, 3, 64, 1760004020, 1, 0),
    (10124, 4, 1, 1760004200, 1, 0),
    (10124, 4, 1, 1760004260, 1, 0),
    (10124, 5, 128, 1760004400, 3, 0),
    (10124, 5, 128, 1760004450, 2, 0),
    (10124, 6, 1, 1760004800, 1, 0),
    (10124, 6, 1, 1760004900, 0, 1),
    (1010123, 7, 2, 1760005100, 1, 0);

INSERT INTO meta VALUES
    ('started_at', 1760000000),
    ('last_flush', 1760007710),
    ('events_seen', 1234567),
    ('events_kept', 4321),
    ('events_unresolved', 12),
    ('events_dropped', 3),
    ('coverage_files', 1),
    ('coverage_exec', 1),
    ('coverage_network', 1),
    ('coverage_process', 1),
    ('coverage_netlink', 0),
    ('coverage_appops', 1),
    ('coverage_binder', -1);
