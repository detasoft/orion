# Git Native Client Output Design

## Goal

Introduce the first typed outbound boundary used by the Git wire
continuations without moving transport orchestration into the wire machine.

## Design

`GitNativeClientOutput` accepts a typed legacy upload-pack advertisement and
encodes it into Git pkt-lines. The output writes the complete encoded
advertisement to a caller-supplied, fixed-size 64 KiB `ByteBuf`. A successful write allows
the active continuation to transition immediately and continue consuming the
current inbound buffer. A rejected write leaves the buffer unchanged so the
continuation can yield and retry after the handler creates capacity.

The output does not implement `Continuation`, call `resumeTask`, or depend on
Netty channels. `GitMinimalWireHandler` remains responsible for draining the
queue, asynchronous channel writes, channel writability, and deciding when a
yielding machine may resume.

The initial slice has one outbound operation only: sending a typed v0/v1
upload-pack advertisement. Generic message encoders and other response types
are deferred until a second concrete output operation exposes a useful common
abstraction.

## Ownership

The caller owns the fixed output buffer. `GitNativeClientOutput` advances its
writer index only when the complete advertisement fits. It performs no partial
write when capacity is insufficient. The handler later drains the readable
bytes and creates writable capacity without transferring buffer ownership to
the continuation.

## Testing

Focused tests cover the encoded first ref and capabilities, following refs and
the terminal flush packet. They also cover an unchanged writer index when the
complete advertisement does not fit.
