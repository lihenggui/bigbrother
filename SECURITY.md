# Security policy

## Supported versions

Security support starts with v0.3.0. Security fixes land on the latest
release.

## Reporting a vulnerability

Report vulnerabilities privately through [GitHub Security Advisories](https://github.com/lihenggui/bigbrother/security/advisories/new).
Do not open a public issue for a vulnerability.

Include the affected Big Brother version, Android version, kernel version
and root manager. Describe how to reproduce the issue and what impact it
has. Share only the logs needed to explain the issue and remove sensitive
personal data before attaching them.

The maintainer will acknowledge the report in the advisory and coordinate
the fix and disclosure with the reporter. Keep the details private until
the agreed disclosure date.

## Scope

- eBPF programs (`bb-ebpf/`)
- Root daemon (`bbd/`)
- Root module scripts (`module/`)
- Android root helpers (`android/helper/`)
- LSPosed `system_server` and content provider hooks (`android/hook/`)
- Android app (`android/app/`)

Vulnerabilities in these components include privilege escalation, arbitrary
code execution, command or path injection, symlink attacks,
time-of-check/time-of-use (TOCTOU) attacks, `system_server` crashes and
privacy leaks.

Big Brother assumes the device is already rooted. Root privilege escalation
by a malicious app outside Big Brother's attack surface is out of scope.
