# Password-Authenticated SSH Key Enrollment Design

## Purpose

Replace Orion's separate one-time SSH enrollment token with the named Orion
user's existing password. A user who proves ownership of an unregistered SSH
public key can authenticate with that password, enroll selected keys, and run
the originally requested shell or exec command on the same connection.

The application-token flow exposed by `issue-token` is unrelated and remains
unchanged.

## Authentication Architecture

The SSH endpoint keeps two distinct identity paths:

- `git@orion` authenticates only by resolving a registered public key to one
  unambiguous ACL user. Git upload and receive behavior does not change.
- A named Orion user first attempts direct public-key authentication. A
  registered key succeeds immediately. An ownership-proven but unregistered
  key is retained only as a connection-local enrollment candidate and the
  public-key authentication attempt still fails.
- Keyboard-interactive authentication is available only to existing named
  Orion users. It verifies the password through
  `OrionAccessControlService.authenticateUser` and installs the returned
  identity in the SSH session only after the complete required exchange
  succeeds.

Candidate collection remains in the proof-aware public-key user-auth factory.
Mina calls the public-key authenticator before signature verification, so the
factory must continue converting a successfully verified unknown key into an
authentication failure after recording it. Probes without a valid signature
never become candidates.

## Conditional Keyboard-Interactive Flow

Apache Mina SSHD 2.13.2's stock server
`UserAuthKeyboardInteractive` generates one challenge and treats the response
as the final authentication result. Orion therefore supplies a small custom
keyboard-interactive user-auth factory and state machine.

The state machine performs these transitions within one SSH authentication
attempt:

1. Send one hidden `Orion password:` prompt. The challenge name and
   instruction do not list candidates or reveal whether candidates exist.
2. Convert the response to UTF-8 bytes, call `authenticateUser`, and clear the
   mutable byte buffer in a `finally` block. An invalid password ends the
   attempt without a second prompt.
3. If the password is valid and there are no proven candidates, attach the
   returned Orion identity to the session and authenticate immediately.
4. If the password is valid and candidates exist, send a second RFC 4256
   information request on the same user-auth instance. Its instruction lists
   deduplicated key algorithms and fingerprints, and its single prompt accepts
   `all`, a comma-separated numeric selection, or a pasted OpenSSH public key.
5. Parse and validate the selection, atomically add the selected keys through
   the ACL service, attach the already verified identity, and return successful
   authentication. Mina then continues the shell or exec request already made
   by the client.

This is a real multi-round exchange: password success with candidates is not
reported to Mina as either failed or complete until the selection response is
processed. It does not use a second authentication attempt as hidden state.

The password-first ordering fixes the observed failure in which a displayed
key list and selection prompt preceded the hidden secret prompt, causing a
typed `1` to be consumed as the secret and producing repeated failed screens.

## Candidate and Credential Handling

Proven candidates are stored as public keys in an insertion-ordered map on the
Mina `ServerSession`, keyed by fingerprint. Repeated offers are deduplicated,
and another connection cannot observe or select them. Candidate instructions
show only algorithm and fingerprint, never complete key material.

`all` selects every candidate. Numeric input must name existing unique
one-based entries. A pasted key must parse and resolve through Mina's OpenSSH
key support. Empty, duplicate, out-of-range, or malformed selections fail the
authentication attempt without changing the ACL.

The ACL service remains responsible for canonicalization, key-byte
deduplication, serialization under its reload lock, one atomic save, and one
reload for the selected batch.

Passwords are never logged or persisted in plaintext. Orion retains neither a
password response nor an authenticated identity in session attributes after a
failed exchange. Mutable UTF-8 buffers are cleared where feasible; Java and
Mina expose prompt responses as immutable `String` values, so those instances
cannot themselves be zeroed.

## Failure and Security Behavior

- Unknown users and `git` receive no keyboard-interactive challenge.
- An invalid password reveals no candidate count, algorithm, fingerprint, or
  selection prompt and cannot add keys.
- A valid password without candidates authenticates the named user directly.
- Invalid key selection fails closed and does not partially persist keys.
- Direct registered-key authentication never invokes keyboard-interactive.
- Candidate public keys never authenticate by themselves.
- Successful enrollment authenticates the current connection; Orion does not
  disconnect or require a second SSH invocation.
- Expected authentication failures use generic SSH failure behavior. Logs may
  contain the attempted username but never the password or pasted key response.

## Migration and Removals

Remove `SshEnrollmentTokenStore`, its state file, startup token output,
regeneration launch option, and the runtime-options binding that existed only
for regeneration. Existing stale token-state files are ignored after upgrade;
no migration or deletion is required.

Update the local Make enrollment helper to require `ORION_ROOT_PASSWORD`, feed
it only through `SSH_ASKPASS`, and answer the post-password key prompt with
`all`. Configure `IdentityFile=none` as well as `IdentitiesOnly=yes`, then pass
the generated admin identity explicitly so default keys in `~/.ssh` cannot
become accidental candidates. Update README and umbrella SSH plan text to
describe password-authenticated, same-connection enrollment.

Do not alter ACL password hashing, root-password generation, bearer-token
issuance, the `issue-token` command, or protected key-material passwords.

## Tests

Use a Mina loopback client/server test to prove the protocol sequence rather
than invoking authenticator methods directly. The test records client prompt
callbacks and verifies:

- round one contains exactly one hidden password prompt and no candidate data;
- invalid passwords stop after round one and persist nothing;
- valid passwords with candidates receive a second prompt containing
  deduplicated algorithms and fingerprints;
- selected keys persist and the same client session becomes authenticated;
- valid passwords without candidates authenticate in one round;
- registered named-user keys and `git` key-derived authentication still work;
- probes, unknown users, malformed selection, and cross-connection candidate
  leakage fail safely.

Service and end-to-end tests cover configured authentication factories,
continuation into the requested command, absence of token state/output, ACL
reload persistence, and preserved Git SSH operations. Makefile tests record
the exact SSH arguments and askpass values, including `IdentityFile=none` and
the absence of `ORION_SSH_ENROLLMENT_TOKEN`.
