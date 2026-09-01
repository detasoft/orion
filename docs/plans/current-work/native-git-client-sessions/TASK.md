# Add Blocking Native Git Clients on Virtual Threads

Status: complete
Source: reformulated from the former native Git client state-machine task.
Detailed design: ../../2026-09-01-virtual-thread-native-git-clients.md

- [x] Add blocking upload-pack and receive-pack client sessions on virtual
  threads before remote replication work. Reuse the same `BufferedByteInput`,
  `BufferedByteOutput`, and blocking Git wire codecs as the native server path.

Do not add a client state-machine/action layer or drive outbound sessions
through `ContinuationRuntime`. Express protocol phases with ordinary blocking
control flow and typed operation results. Transport cancellation, timeout, and
backpressure belong at the blocking session boundary.
