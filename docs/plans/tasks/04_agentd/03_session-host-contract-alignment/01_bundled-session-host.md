# Bundle Session Host with AgentD

Status: todo
Parent: TASK.md

Package the native `session-host` built for the current distribution platform
inside the AgentD artifact and make it the default executable for daemon and
local-terminal launches. Preserve `--session-host PATH` as an explicit override
that bypasses bundled-host installation.

## Requirements

- Include the `session-host` native resource in the produced AgentD JAR rather
  than requiring a separate installed binary.
- When `--session-host` is absent, install the bundled executable at
  `<state-dir>/runtime/session-host` before any host launch.
- Use the resource timestamp as the first content boundary. If a regular,
  executable installed file has the same timestamp, reuse it without hashing.
- When timestamps differ or are unavailable, calculate SHA-256 directly from
  the bundled resource and compare it with the installed file. If they match,
  update only the installed timestamp and reuse the file.
- When checksums differ or no installed file exists, copy the resource to a
  unique sibling temporary file, give it owner-only executable permissions on
  POSIX, set the resource timestamp when available, and atomically replace the
  installed path. Never expose a partial executable at the final path.
- Allow concurrent AgentD invocations to converge on the same complete bundled
  executable without sharing a temporary file.
- Report missing resources and installation, checksum, permission, or atomic
  replacement failures before attempting to launch a session host.
- When `--session-host PATH` is present, retain current path validation and use
  that executable without reading or installing the bundled resource.

## Design

A small AgentD-owned bundled-host installer resolves the native resource and
owns its timestamp, SHA-256 comparison, executable permissions, and atomic
publication. Both daemon argument resolution and `terminal start` call this
single implementation only when no explicit executable override was supplied.
Downstream runtime and session-host launch contracts continue to receive one
absolute executable path and remain unchanged.

The Maven build unpacks only the native session-host resource from the existing
reactor artifact into AgentD's output before its JAR is created. The runtime
installer selects the resource for the current OS and architecture; an AgentD
artifact built without a matching resource fails with a bounded diagnostic.

## Implementation plan

1. Add focused installer tests for initial installation, timestamp fast-path,
   checksum reuse with timestamp repair, checksum-driven atomic replacement,
   executable permissions, and missing or failed resource installation.
2. Implement the smallest bundled-host installer that satisfies those tests.
3. Add daemon and local-terminal command tests proving omission uses the
   installed resource and an explicit `--session-host` bypasses installation.
4. Make the CLI option optional, resolve the bundled executable before launch,
   and update help and local-terminal documentation.
5. Embed the reactor-built native resource in the AgentD JAR and verify both
   resource presence and a real default local terminal launch.

## Dependencies

- The existing `session-host` Maven module must produce the native resource for
  the distribution platform before AgentD packaging.
- This task does not depend on the remaining server command or journal-sync
  orchestration work.

## Acceptance

- The produced AgentD artifact starts daemon and local terminal sessions without
  `--session-host`, using `<state-dir>/runtime/session-host`.
- An unchanged installed binary takes the timestamp fast-path; a timestamp-only
  difference is repaired after matching SHA-256; different content is replaced
  atomically before launch.
- An explicit `--session-host PATH` continues to launch exactly that executable
  and does not install the bundled copy.
- Focused tests cover both straightforward installation and meaningful update,
  failure, and override scenarios; the full JVM and native-host verification
  required by the changed build inputs passes.
