# SSH Credential Commands Design

## Purpose

Add self-service SSH credential management to the Orion command tree without
granting users access to other ACL state. An authenticated user can list, add,
and remove only their own OpenSSH public-key credentials through the same
handlers used by SSH exec and the interactive terminal.

The commands are:

```text
/auth/key ls
/auth/key add candidates=all
/auth/key add candidates=<fingerprint-prefix,...>
/auth/key add key='<OpenSSH-public-key>'
/auth/key rm <fingerprint-prefix>
/auth/key rm <fingerprint-prefix> --force
```

The final form deliberately permits an operator to remove the last usable SSH
key, but only after an explicit `--force`. Removing the key used to authenticate
the current connection does not terminate that already authenticated session.

## Architecture

Credential handlers remain ordinary command handlers. They receive no Mina
session or channel objects. The SSH frontend copies only the credential facts
needed by commands into an immutable connection credential context: the
fingerprint of the public key that authenticated the connection, when present,
and canonical OpenSSH public keys whose ownership was proved during that SSH
connection. Both exec and interactive requests carry the same context.

The authorization API exposes narrow typed operations for listing, adding, and
removing SSH credentials. It does not reuse whole-user replacement, because a
read-modify-write through `AccessControlUserUpdate` could overwrite concurrent
roles, grants, passwords, or unrelated credentials. The ACL implementation
parses keys before mutation and performs each change atomically under its
existing reload lock.

The reload lock serializes callers in one process, but it cannot exclude a
reset or another Orion process writing the native ACL repository. A native ACL
snapshot's Git commit version is therefore also its write precondition. The
native repository builds the replacement commit from that exact commit and
updates the configured ref with compare-and-set against the same object ID. If
the ref has advanced, the save reports a dedicated concurrent-update outcome
and leaves the newer ref and its files untouched. Snapshots without a version
retain the existing unconditional save behavior, which is needed for initial
creation and local-file storage.

All operations identify the target user from the authenticated
`SecurityContext`. The command syntax contains no user selector, so a caller
cannot use these commands to inspect or edit another principal.

## Listing and Fingerprints

`/auth/key ls` returns structured rows with `algorithm`, `fingerprint`, and
`current` columns. It never returns encoded key material. Fingerprints use
Mina's standard SHA-256 representation and comparisons are case-sensitive
after trimming.

The ACL service canonicalizes and deduplicates keys by encoded public-key bytes.
If legacy ACL data contains duplicate records for the same key, listing exposes
one logical entry and removal deletes all duplicate records for that key.
Malformed configured OpenSSH credentials fail the operation explicitly instead
of being silently hidden.

The `current` column is true only for direct public-key authentication with the
listed fingerprint. Password-authenticated sessions have no current SSH key,
including sessions that enrolled a key during authentication.

## Adding Keys

`candidates=all` adds every ownership-proven, still-unregistered candidate held
by the current connection. A comma-separated value selects candidates by
unambiguous fingerprint prefix. Empty, unknown, repeated, or ambiguous
selectors fail without writing the ACL.

`key=...` accepts one manually pasted OpenSSH public key. The two input forms
are mutually exclusive. Candidate keys and pasted keys pass through the same
ACL parsing, byte-level deduplication, canonical serialization, one-save, and
one-reload path. Adding an existing key succeeds idempotently and reports that
no credential changed.

The full pasted key is a sensitive named parameter and is always redacted by
the existing command audit describer. Candidate fingerprints may be audited;
full candidate key material never enters audit metadata or log messages.

The proved-candidate set is connection-local immutable data. It is not placed
in general audit metadata and cannot be observed from another SSH connection.

## Removing Keys

`/auth/key rm <fingerprint-prefix>` matches the authenticated user's logical
SSH keys. No match and multiple matches return distinct typed failures with no
mutation. A nonblank unique prefix may identify the complete fingerprint.

Removing a key that leaves at least one other SSH key needs no flag. Removing
the last SSH key requires `--force`; the parser treats a standalone boolean
flag as the named parameter `force=true`, and command-definition validation
rejects flags not declared by that command.

Successful removal takes effect for new authentication attempts immediately.
If the removed key authenticated the current connection, the existing session
remains valid until its normal disconnect. Audit records identify the command,
fingerprint prefix, force choice, user, connection, and outcome without key
material.

For non-root users, forced removal may leave a key-only identity unable to open
another SSH session. A configured password or administrative ACL repair remains
its recovery route. This explicit lockout is the meaning of `--force`.

## Locked Root State

A generation-aware root cannot become a legacy root merely because its last
SSH credential was removed. Doing so would discard the authentication
generation and could make older root JWTs valid again.

Forced removal of the last root SSH key therefore rotates the root generation
and replaces the key with one internal, non-authenticatable locked marker. The
marker uses a dedicated reserved key-ID prefix and a one-way random
password-shaped value whose plaintext is never returned or retained. It is not
listed as an SSH key and cannot authenticate by password or public key.

The locked state has these rules:

- existing and newly presented root JWTs fail;
- token issue for a stale in-memory root identity fails;
- internal server-key synchronization does not inject a key;
- `/auth/key add` cannot unlock root from the still-open session;
- normal restarts preserve the locked state;
- only a new `--reset-root-pass` recreates the canonical recovery root.

The session that executed the forced removal remains an authenticated command
session as requested, but the credential API refuses further root additions
while the persisted root is locked. Other already-authorized administrative
commands retain their normal session semantics.

## Command Tree and Presentation

The existing command catalog gains static `/auth/key` nodes and `ls`, `add`,
and `rm` definitions. Authorization requires an authenticated named user; the
handlers always derive the concrete user ID from that identity.

Handlers return `Rows`, `Message`, or typed command failures. They do not write
to SSH streams or prompt interactively, so exec and terminal rendering remain
equivalent. The generic parser gains standalone long boolean flags such as
`--force`; quoted `key=...` remains a normal sensitive named value.

Help and completion expose the static path/actions and declared parameters.
They do not expose candidate keys or key material.

## Failure and Concurrency Behavior

- Parse and validate every requested key and selector before mutation.
- Resolve the user and current persisted credentials again under the ACL reload
  lock immediately before saving.
- Serialize only the intended user's credentials while preserving all other
  users, roles, grants, and non-SSH credentials.
- Return expected validation, ambiguity, last-key, and locked-root outcomes as
  result values rather than expected control-flow exceptions.
- Save and activate one ACL update per successful add or removal.
- Do not partially add or remove when one requested item is invalid.
- For versioned native snapshots, build and compare-and-set from the exact
  loaded version rather than resolving the branch again at save time.
- A concurrent reset or credential update wins according to the stored ACL
  version; the losing operation reports `CONCURRENT_UPDATE` rather than
  overwriting it.
- Keep versionless local and initial-creation saves on their existing path;
  their in-process credential mutations remain serialized by the reload lock.

## Testing

Authorization and ACL tests cover listing, algorithms, canonical fingerprints,
duplicate and malformed stored keys, idempotent addition, invalid pasted keys,
unambiguous candidate selection, ACL isolation, and concurrent-state failures.
Native storage tests advance the configuration ref after loading a snapshot,
then prove a stale conditional save is rejected and cannot replace either the
newer ACL bytes or unrelated files from the winning commit.

Removal tests cover missing and ambiguous prefixes, duplicate records, current
key removal, last-key refusal, forced non-root lockout, generation preservation
with remaining root keys, and forced root locking. Root tests also cover token
revocation, blocked token issue and key addition, restart persistence, skipped
server-key synchronization, and recovery by a later reset.

Command tests cover `/auth/key` routing, authorization, argument combinations,
`--force` parsing and validation, structured output, sensitive-value audit
redaction, and identical exec/interactive handling. Mina loopback coverage
proves isolation of candidate sets, accurate current-key marking, immediate use
of newly added keys, and survival of the current session after its key is
removed.
