# Protocol cases

The daemon and the app share line formats, each implemented on both sides.
Each file here lists the lines of one format with what they mean, and the
tests of both sides read it: the side that writes a line checks that it
writes exactly that line for the meaning, the side that reads it checks that
it reads that meaning from it and rejects the lines listed as bad.

| File | Daemon | App |
| --- | --- | --- |
| `appops.txt` | `bbd/src/sandbox/appops.rs` | `AppOpsGuardProtocol.kt` (`:app`) |
| `spool.txt` | `bbd/src/spool.rs` | `EventSpool.kt` (`:hook`) |
| `sandbox-list.txt` | `bbd/src/sandbox/identity.rs` | `SandboxRules.kt` (`:hook`) |
| `sandbox-status.txt` | `bbd/src/sandbox/identity.rs` | `SandboxRules.kt` (`:hook`) |
| `bbd-cli-errors.txt` | `bbd/src/protocol_cases.rs` | `BbdFailureTest.kt` (`:core:data`) |

A change to a format changes its file, and both sides with it.

## Format

One case per line, its fields separated by tabs: the type of the case, the
line, then the words of the meaning. Lines starting with `#` and empty lines
are skipped. A case with nothing after its type is an empty line.

In the line and in the words, `\t` stands for a tab, `\n` for a line break
and `\\` for a backslash. A word is `<key>=<value>`, or a key alone. Each
file says at the top what its types and words are; a case of another type,
or a word with another key, fails the tests.

The readers are `bbd/src/protocol_cases.rs` and `ProtocolCase.kt` in the
tests of `:app` and `:hook`.
