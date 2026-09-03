# Consolidate Typed Agent Protocol Stream Decoding

Status: todo
Design: ../../../2026-09-03-typed-agent-protocol-stream-decoding-design.md
Detailed plan: ../../../2026-09-03-typed-agent-protocol-stream-decoding.md

Make `agent-protocol` the single owner of incremental CBOR Sequence parsing
and let AgentD receive decoded protocol values instead of untyped item bytes.

## Scope

- Delete
  `agentd/src/main/java/pro/deta/orion/agentd/transport/CborSequenceDecoder.java`
  and migrate its useful bounded incremental behavior into `agent-protocol`.
- Accept the Jetty-provided `ByteBuffer`, decode each complete item immediately,
  and emit typed `AgentMessage` or `SessionEventRecord` values.
- Keep one reusable owned buffer for incomplete input; remove combined-buffer
  and per-item transport copies that exist only between framing and decoding.
- Report a structurally bounded but semantically undecodable item as an error,
  log it in AgentD, skip it, and continue with later items on the same stream.
- Preserve already decoded items before any later failure, independent of how
  HTTP/2 DATA chunks divide the same byte sequence.
- End only the affected stream or connection when the next CBOR item boundary
  cannot be found safely; do not attempt heuristic resynchronization.
- Preserve opaque unknown messages, journal payloads, future tails, strict
  validation, and configured structural bounds.

## Boundary

This task does not add a length prefix, magic marker, or Netty `ByteBuf`, and it
does not change protocol v1 wire bytes. Higher-level authentication, message
ordering, and session policy remain outside the sequence parser.
