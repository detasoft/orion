# Move Acknowledged Retention Off the Writer Path

Status: todo
Depends on:
completed explicit journal durability,
completed `ACK_JOURNAL` durability and retention gate

Complete `ACK_JOURNAL` after its permission is durable without holding shared
host state while physical retention scans and deletes journal segments.

## Scope

- Under the existing serialized control boundary, durably advance the monotonic
  acknowledgement watermark and update the operation ledger.
- Submit the greatest acknowledged watermark to a coalescing maintenance queue,
  release shared state, and return the ACK without waiting for physical
  deletion.
- Make maintenance retryable from the durable watermark after worker failure or
  a later maintenance trigger; deletion remains optional physical cleanup, not
  part of ACK success.
- Remove the synchronous result channel from acknowledged retention and keep
  journal writes and unrelated control operations independent of maintenance.
- Plan retention from segment sizes first. Decode only the oldest closed prefix
  that may need deletion, stopping once the size target is met or a segment is
  not fully covered by the acknowledged watermark.
- Avoid fully decompressing retained segments that cannot be deletion
  candidates. Preserve ordering, corruption handling, and safe reader races.
- Preserve the `ACK_JOURNAL` wire contract and its meaning as durable deletion
  permission rather than completed deletion.

## Acceptance

- Slow, failed, or retried maintenance does not delay ACK after its watermark is
  durable and does not block PTY journal appends or other control operations.
- Repeated and increasing ACKs coalesce monotonically without losing the newest
  watermark; later maintenance can retry unfinished physical cleanup.
- A journal already within its size target triggers no segment decoding, and
  an oversized journal scans only the oldest prefix needed for a deletion
  decision, including compressed candidates.
- Tests cover concurrent writes and controls during stalled retention, worker
  failure and retry, coalescing ACKs, protected unacknowledged segments,
  corruption, and reader/deletion races.
