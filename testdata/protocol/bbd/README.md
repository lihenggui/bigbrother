# Android daemon CLI responses

These fixtures exercise the JSON consumed by `RootShellBbdDataSource` in
`:core:data`. They use made-up packages and records. CLI error fixtures
shared with the daemon are in [bbd-cli-errors.txt](../bbd-cli-errors.txt).

| Command after `bbd` | Arguments | Fields read |
| --- | --- | --- |
| `apps` | Optional `--hours HOURS` | Array of `uid`, `pkg`, per-category counts, `last_ts`, `files_read`, `folders`, `folder_count`, `topics` |
| `files` (app records) | `--uid UID --limit LIMIT --offset OFFSET`, optional `--cat CATEGORY` and `--hours HOURS` | Array of `cat`, `mode`, `path`, `items`, `ok`, `fail`, `last_ts`, `topic` |
| `files` (search) | `--contains=TEXT`, repeated `--cat CATEGORY` and `--uid UID`, `--limit LIMIT`, optional `--hours HOURS` | The same rows plus `uid` and `pkg` |
| `history` | `--uid UID`, repeated `--path=PATH`, optional `--hours HOURS` | Array of `mode`, `ok`, `fail`, `ts`, `path` |
| `sensors` | `--uid UID`, optional `--hours HOURS` | Array of `sensor`, `high_rate`, `period_ns`, `rate_hz`, `start_ts`, `end_ts` |
| `timeline` | `--limit LIMIT`, optional `--before TIMESTAMP` and `--hours HOURS` | `next_before` and `entries` with `hour_ts`, `uid`, `pkg`, `cat`, `topic`, `paths`, `files_read`, `ok`, `fail`, `modes`, `items`, `folder`, `first_ts`, `last_ts`, and `subjects` rows with `path`, `ok`, `fail` |
| `stats` | None | Recorder status reads `daemon_running` and the `coverage_*` keys under `meta`. Diagnostics exports the raw combined stdout and stderr. |
| `net activity` | `PACKAGE --since SINCE`, optional `--until UNTIL`, `--bucket hour\|day`, optional `--day-starts=STARTS`, `--user USER --json` | `since`, `until`, `bucket`, `recording.on`, `recording.since`, `network`, `traffic`, `names`, `buckets`, `destinations`, `rules` |
| `net destination` | `PACKAGE TARGET` and the activity range flags, `--user USER --json` | A destination object and its `buckets` and `bucket` |
| `net block` | `PACKAGE TARGET`, optional `--manual`, `--user USER --json` | Shared network fields only |
| `net unblock` | `PACKAGE TARGET --user USER --json` | Shared network fields only |
| `net clear` | `PACKAGE --user USER --json` | `deleted` |
| `sandbox list` | `--json` | `apps` rows with `uid`, `package`, `enabled`, `created`, `settings`, `items`, `unmet`, `appops_checking`, `network_rules`, `recording_since` |
| `sandbox global` (read) | `--json` | `items`, `auto` |
| `sandbox status` | `--json` | `gate`, `appops`, `hook` with `android_id`, `app_list`, `uri_grants`, `ad_id`, plus `new_apps_pending`, `new_apps_held`, `network`, `net_record`, `dns_names`, `traffic` |
| `sandbox enable` | `PACKAGE --user USER --json` | Exit status |
| `sandbox disable` | `PACKAGE --user USER --json` | Exit status |
| `sandbox disable` (all) | `--all --json` | Length of the `failed` array |
| `sandbox set` | `PACKAGE ITEM=MODE ... --user USER --json`, using `global` to reset an item | Exit status |
| `sandbox global` (write) | `ITEM=MODE ... --json` | Exit status |
| `sandbox auto` | `on\|off --json` | Exit status |

In `sandbox list`, `unmet.network_record` can contain `dns_names` when
connections are recorded without domain names.

Network responses share `ok` and optional `error` fields. Bucket entries
contain `t`, `conn`, `refused`. Rule entries contain `target`, `created`,
`manual`. A destination contains `site` or `addr`, and can include `hosts`
(by `name`), `ports` (by `port`), `addrs`, `addr_count`, `first`, and
`last`. Destinations, hosts, and ports can each carry `conn`, `refused`,
`sent`, `recv`, and `rule`. Day buckets send explicit bounds and the
local day starts. Hour buckets omit those starts.
