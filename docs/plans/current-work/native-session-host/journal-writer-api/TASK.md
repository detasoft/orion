# Reduce the Journal Writer API

Status: todo
Depends on: completed native journal core and metadata manifest

Model one `JournalWriter` per host incarnation and remove recovery and format
parameters that the current CBOR Sequence lifecycle no longer supports.

## Scope

- Confirm that the Rust crate has no external consumers requiring the APIs
  before removing them.
- Keep `JournalWriter::create` as the production construction path and remove
  writer recovery for a failed host incarnation.
- Remove the stored and exposed `journal_id`; journal identity is not part of
  the current CBOR Sequence records.
- Remove per-append payload schema and flags parameters. Encode the sole
  supported values internally instead of publishing legacy choices.
- Remove the public Rust `read_after` API. Keep only the minimal internal scan
  needed for validation and retention, and move general read helpers used only
  by tests into test support.
- Preserve persisted journal bytes and the cross-process wire contract.

## Acceptance

- Production exposes no writer-recovery lifecycle or obsolete journal identity,
  schema, and flags choices.
- Validation and retention share the smallest scanner needed by production;
  tests do not force a general reader into the runtime API.
- Existing CBOR fixtures remain byte-identical, and creation, append, rotation,
  validation, retention, and crash-tail coverage continues to pass.
