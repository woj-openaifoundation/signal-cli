# Read-only Signal context fork

Based on upstream signal-cli v0.14.8. This distribution is intended for an
already linked signal-cli account and explicit, on-demand context retrieval.
It does not read the Signal Desktop database or the history on a phone.

## Boundaries

- Only `receive`, `listAccounts`, `listContacts`, `listGroups`, `listIdentities`,
  and `version` are exposed. JSON is the default output format.
- Sending messages, reactions, receipts, typing indicators, calls, account or
  group edits, registration/linking, daemon mode, D-Bus and JSON-RPC are not
  exposed. Global config files are ignored so they cannot enable unsafe defaults.
- Incoming message envelopes are held in memory, not cached on disk. No
  attachment, long-text attachment, avatar, sticker, story, contact-sync or
  group-sync blob is downloaded. No message index or background archive is created.
- Receipt messages and automatic message/sync responses are suppressed. Receiving
  still uses Signal transport acknowledgements, which consume this linked
  device's pending server queue, and uploads replacement cryptographic pre-keys
  when needed. It is not a network client that performs only HTTP reads.
- Application logging is disabled. The installed launcher disables JVM heap/core
  dumps and ignores JVM environment options that could re-enable diagnostic files.

## Storage that still exists

This is **not a zero-storage or zero-retention system**. Authentication keys,
identity/session/sender-key records, recipient identity mappings and necessary
account state remain in signal-cli's existing local account directory. SQLite may
use journal/WAL files for that state. Existing account/contact/group metadata is not
deleted or copied elsewhere by installation. Local list commands can expose that
existing metadata, which may be stale because sync/profile refresh is disabled.

Message text is emitted to standard output. A caller can save that output; Codex
can retain it in its chat/session history and sends selected context for model
processing under its own data policies. This fork cannot control Codex retention,
shell redirection, operating-system swap, backups or a user's separate Signal
installations. It does not provide an OS security boundary against someone who
can execute arbitrary code or install another client.

## Operational tradeoffs

There is no message history, replay, indexing or recovery queue. Each receive
retrieves only messages currently pending for this linked device. A crash,
decryption failure or output-consumer failure after acknowledgement can lose a
message for this device; the copy on the phone is independent. Disabling incoming
sync processing also means later account/device changes may require manual
maintenance or re-linking using a separately managed client.

Attachments (including long messages encoded as attachments) are unavailable.
New sender/group names may be unavailable; identifiers remain usable. This fork
must be kept current with Signal's protocol changes.

## Use

After installation, inspect the restricted command surface without opening an account:

```sh
signal-cli --version
signal-cli --help
```

To explicitly retrieve pending messages into the calling process, after accepting
the retention boundaries above:

```sh
signal-cli receive --timeout 3 --max-messages 50
```

Select an existing account with `-a` or an existing account directory with
`--data-dir` when necessary. Do not redirect the output to a file if you do not
want another copy. There is no scheduler or automatic Codex ingestion.

## Build and install on macOS

JDK 25 is required. With Homebrew's `openjdk@25` already installed:

```sh
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
./gradlew --no-daemon test installDist
./scripts/install-readonly-macos.sh
```

The installer puts the distribution under Homebrew's prefix at
`opt/signal-cli-readonly`, and creates `bin/signal-cli-readonly`. To make it the
default `signal-cli`, first run `brew unlink signal-cli`, then create a symlink
from `bin/signal-cli` to `bin/signal-cli-readonly`. The upstream Homebrew package
can remain installed for rollback; it is a separate, unrestricted executable.
Future Homebrew relinking can change which executable the `signal-cli` name runs.
Use `signal-cli-readonly` for an unambiguous path to this fork.

To restore the upstream default, remove only the `bin/signal-cli` symlink pointing
to this fork, then run `brew link signal-cli`. This does not change account data.

Tests use synthetic data and temporary directories. Installation itself neither
links an account nor receives messages.
