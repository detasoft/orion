# Bounded Streaming Pack Ingestion Design

## Goal

Connect legacy receive-pack wire handling to native storage without retaining
the complete pack in a `ByteBuf`, `CompositeByteBuf`, byte array, file, or other
intermediate collection. Parse each incoming fragment once, stage completed
objects in an isolated quarantine, and expose that quarantine only after the
pack trailer checksum is valid.

This slice ends at the checked quarantine handoff. Publishing objects, applying
ref updates, and writing report-status remain separate receive-pack work.

## Ownership Boundary

`NativeGitRepository` owns creation of a pack-ingestion session. The repository
supplies its published object store for delta-base lookup and keeps storage
details out of the wire module.

The session accepts caller-owned `ByteBuf` fragments:

```java
PackIngestionSession session = repository.beginPackIngestion(limits);
PackIngestionResult result = session.accept(fragment);
```

`accept` advances the fragment's reader index but never retains or releases the
fragment. Its result distinguishes `NeedInput`, `Complete`, and `Failed`.
`Complete` transfers one checked quarantine exactly once. `close` is
idempotent, cancels incomplete ingestion, and prevents later access to staged
objects.

The receive-pack continuation owns the session lifecycle. It forwards raw input
fragments until completion, closes the session on wire shutdown or failure, and
transitions to later push processing only after `Complete`.

## Incremental Decoder

The decoder lives in `core/git-native-storage`. It keeps only bounded parsing
state:

- the partial fixed pack header and current object header;
- object count and current pack position;
- the running pack SHA-1 digest;
- one `Inflater` and a small reusable compressed-input scratch buffer;
- the current object's declared metadata and bounded inflated content;
- offsets and object identifiers needed for delta-base resolution;
- up to 20 partial trailer bytes.

It does not retain input fragments or copy their unread remainder into a
long-lived pack buffer. Header fields may use fixed-size arrays. Compressed
bytes are fed incrementally into `Inflater`; they are not preserved after the
inflater consumes them.

Each completed whole object is written immediately to the session's private
`LooseObjectStore` quarantine. Delta objects resolve against previously staged
objects or the repository's published object store, then write their resolved
object to quarantine. Per-object buffering remains bounded by the declared
inflated size and a configured object-size limit because the current loose
object API writes canonical object data as `byte[]`.

The decoder updates the pack digest for every byte from `PACK` through the end
of the final compressed object. After the declared object count is exhausted,
it reads exactly 20 trailer bytes without adding them to the digest. A matching
checksum completes the session. A fragment containing bytes after the checksum
is rejected because this slice does not support push-options or another
post-pack section.

## Limits and Failures

The session enforces independent configured limits for:

- total pack bytes, including the trailer;
- declared object count;
- inflated bytes for one object.

Failures are typed as incomplete input, malformed pack, or limit exceeded.
Malformed magic, unsupported version or object type, invalid object headers,
invalid deflate data, unavailable delta bases, size mismatches, checksum
mismatch, and bytes after the trailer fail the session. End-of-input or close
before a valid trailer reports incomplete ingestion.

Expected ingestion failures are returned through the result and continuation
flow contracts. They are not thrown as normal `Output` or wire control flow.
After any failure, no quarantine can be handed off and no staged object becomes
visible in the repository.

## Wire Data Flow

The receive-pack graph becomes:

```text
Receive commands
  -> open repository pack-ingestion session
  -> forward caller-owned raw ByteBuf fragments
  -> NeedInput: await the next fragment
  -> Complete: take checked quarantine and continue push processing
  -> Failed: close session and enter receive-pack failure flow
```

No transport adapter knows about pack parsing or quarantine storage. The wire
continuation coordinates input and transitions; native storage owns pack
decoding and staging.

## Testing

Native-storage tests cover:

- a valid pack split at every byte boundary;
- pack header, object header, deflate stream, and trailer split independently;
- multiple objects across fragments and multiple objects in one fragment;
- advancement of the caller's reader index without retain or release;
- successful quarantine transfer exactly once;
- bad checksum and bytes after the checksum;
- truncated input detected on close;
- malformed deflate and malformed headers;
- pack-byte, object-count, and inflated-object limits;
- no visible publication and no accessible quarantine after failure;
- delta-base lookup from quarantine and the published store.

Wire tests cover one receive-pack command section followed by fragmented raw
pack data. They verify that one repository-owned session receives the original
fragments, survives `NeedInput`, hands off only after checksum validation, and
closes on disconnect or malformed input.
