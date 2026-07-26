# ByteBuf Read Checkpoint Design

## Goal

Add a small transactional reader for Netty `ByteBuf` parsing. A parser should be
able to read through this abstraction, commit successful or intentionally
incomplete reads, and automatically roll the original `readerIndex` back when
parsing fails.

## Design

Introduce a package-local `CheckpointedByteBufReader` in the Git wire parser
package. The class wraps a caller-owned `ByteBuf`, records the starting
`readerIndex`, implements `AutoCloseable`, and restores the original
`readerIndex` from `close()` unless `commit()` has been called.

The abstraction is not a `ByteBuf` subclass. It should expose only the small set
of relative operations that parser code needs, such as readable state checks,
primitive reads, skipping bytes, retained slicing, and access to the current
reader position. Keeping the API narrow avoids inheriting Netty's broad buffer
contract and keeps ownership behavior explicit.

Parsers decide which outcomes commit. A completed parse should call `commit()`.
An incomplete parse that has saved consumed bytes in durable parser state, such
as `ControlState.MoreDataNeeded`, should also call `commit()`. Malformed input
or other parser failures should leave the checkpoint uncommitted, so closing the
reader restores the original `readerIndex` before the exception leaves the
parser.

Git-specific parsing helpers, such as pkt-line hex length decoding, should not
be baked into the generic checkpoint class. They should remain in Git wire
helpers or be implemented by the parser on top of the checkpoint's primitive
read methods.

## Initial Scope

Apply the checkpoint first to `GitFixedControlFrameReader`, where invalid
headers currently can consume bytes before the failure is reported. The reader
should continue treating `MoreDataNeeded` as a normal parser result while
malformed complete headers throw parser errors with the current input
`readerIndex` restored.

Tests should cover successful commits, rollback on malformed input, commit for
fragmented `MoreDataNeeded`, and retained-slice ownership for any exposed slice
primitive.
