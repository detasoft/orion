# Add Blocking Native Git Clients on Virtual Threads

Status: in progress
Source: reformulated from the former native Git client state-machine task.
Detailed design: ../../2026-09-01-virtual-thread-native-git-clients.md

- [ ] Add blocking upload-pack and receive-pack client sessions on virtual
  threads before remote replication work. Reuse the same `BufferedByteInput`,
  `BufferedByteOutput`, and blocking Git wire codecs as the native server path.
  - Owner: codex, session codex-6f4c9b, started 2026-09-01 19:41 Europe/Amsterdam.

Do not add a client state-machine/action layer or drive outbound sessions
through `ContinuationRuntime`. Express protocol phases with ordinary blocking
control flow and typed operation results. Transport cancellation, timeout, and
backpressure belong at the blocking session boundary.
