# Retire Asynchronous Native Git Output Buffering

Status: superseded
Source: converted from former root task list Next section.
Replacement plan: ../../2026-08-31-blocking-git-native-client-output.md

The completion-aware double-buffer implementation was replaced by synchronous
`BufferedByteOutput` writes for virtual-thread Git sessions. Blocking transport
writes now provide bounded backpressure and keep buffer ownership local to the
session.

The proposed ring-buffer coordinator is not needed: it would reintroduce an
asynchronous ownership and reclamation layer that the blocking transport path
deliberately removed. Reconsider output buffering only in response to measured
throughput or allocation evidence, not as remaining task work.
