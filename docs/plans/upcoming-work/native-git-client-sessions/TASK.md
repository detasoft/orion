# Add Blocking Native Git Clients on Virtual Threads

Status: todo
Source: reformulated from the former native Git client state-machine task.
Detailed design: ../../2026-09-01-virtual-thread-native-git-clients.md

Before remote replication work, add blocking upload-pack and receive-pack
client sessions on virtual threads. Reuse the same `BufferedByteInput`,
`BufferedByteOutput`, and blocking Git wire codecs as the native server path.

Do not add a client state-machine/action layer or drive outbound sessions
through `ContinuationRuntime`. Express protocol phases with ordinary blocking
control flow and typed operation results. Transport cancellation, timeout, and
backpressure belong at the blocking session boundary.
