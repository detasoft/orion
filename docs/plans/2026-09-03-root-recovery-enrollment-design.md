# One-Time Root Recovery Enrollment Design

## Purpose

Turn `--reset-root-pass` into a root-only recovery operation rather than an
ordinary password replacement. A reset must remove every previous root login
credential and repair root authorization, while leaving all other users and
their authentication behavior untouched.

The operator flow is deliberately sequential:

1. Start Orion with `--reset-root-pass`; Orion persists and activates a fresh
   canonical root and then prints its generated recovery password.
2. `issue-token` and every normal root SSH operation fail. Old root SSH keys
   and old root bearer JWTs also fail.
3. Run `make enroll-admin-key`, enter the generated password at the interactive
   terminal, and select the proved administrator key.
4. Orion atomically replaces the recovery password with the selected SSH key.
5. Run `make issue-token` over a new public-key-authenticated SSH connection.

The recovery password is therefore a password-shaped, Argon2-hashed one-time
credential. It is not accepted as a general root login and is not passed in an
environment variable.

## Root Reset and Canonical Authorization

The reset transaction removes every case-insensitive `root` user definition
from the loaded ACL snapshot and adds one newly generated canonical root to the
primary ACL file. It also replaces the canonical `ROOT` role and its built-in
grants with the definitions from `ACLUtil.generateDefaultAccessControl`, so a
damaged role or missing grant cannot survive recovery.

The new root contains exactly one `ARGON2` credential and exactly the canonical
root role reference. It does not inherit names, email, direct grants, roles,
SSH keys, signing-key credentials, or any other credential from the previous
root. The recovery credential carries a new random root authentication
generation in its existing credential `keyId`; this generation is metadata,
not another authentication mechanism.

All non-root users remain byte-for-byte represented by their existing ACL
entries. Shared canonical root role and grant definitions are repaired in
place; users that already reference those definitions remain resolvable.

The normal internal-server-key synchronization must recognize a recovered root
generation and skip automatic root credential injection. This prevents a
server identity key from silently restoring root SSH access during the reset,
enrollment reload, or a later restart.

The updated snapshot is saved and strictly reloaded before the plaintext
recovery password is printed. Save or reload failure aborts startup and never
prints an unusable password.

## Recovery-Only SSH Authentication

Existing password and key enrollment behavior for non-root users does not
change. Special handling applies only when the named user is `root` and its
credential set is the generated recovery shape.

For that root state, keyboard-interactive authentication requires a proved
unregistered public-key candidate. After the hidden password succeeds and the
operator selects a key, the authenticator stores a connection-local pending
enrollment containing the selected canonical key material and the expected
root generation. It does not mutate the ACL during SSH user authentication.

The resulting SSH session is marked recovery-only. It may execute exactly one
dedicated `enroll-key` command. Interactive shell requests, Git commands,
`issue-token`, and all other exec commands are rejected before dispatch. The
`enroll-key` command asks the ACL service to complete the pending enrollment.
Under the ACL reload lock, the service verifies that root still has the same
single recovery credential, replaces it with the selected SSH key or keys,
copies the root authentication generation into their credential key IDs, saves
once, and reloads once.

If another reset or enrollment won the race, generation or state validation
fails without overwriting the newer ACL. A failed password, invalid selection,
disconnect, or wrong command leaves the recovery password intact. A successful
command removes it, so it cannot enroll another key.

Normal root public-key authentication is available only after this transition.
Further root key changes remain possible through authenticated ACL
administration, but not through the consumed recovery password.

## Root-Only JWT Revocation

Rotating Orion's shared JWT signing identity would revoke tokens for every
user, which violates the root-only requirement. Instead, JWTs issued for a
generation-aware root include that root authentication generation as a payload
claim.

Root bearer authentication compares the claim with the generation carried by
the current root credentials. A token with an old, absent, or malformed claim
fails after reset. The generation is copied from the recovery credential to
the enrolled SSH credentials, so it remains stable across the intended
password-to-key transition and across restarts. Another reset creates a new
generation and immediately revokes the previous root tokens.

Token issue is refused while root is in recovery state even if an internal
caller holds a stale root identity. After key enrollment, the SSH
`issue-token` command issues a token containing the current generation.

JWT issue and verification for non-root subjects remain compatible with their
current claim format and do not consult the root generation. Existing non-root
tokens remain valid until their ordinary expiration or signing-key lifecycle
invalidates them.

## Operator Commands

`make enroll-admin-key` creates the local admin key when needed and invokes SSH
on the real terminal with public-key plus keyboard-interactive authentication
and the dedicated `enroll-key` command. It does not require or read
`ORION_ROOT_PASSWORD`, does not use `SSH_ASKPASS`, and allows the operator to
enter the hidden password and visible key selection normally.

`make issue-token` and `make issue-token-raw` use public-key-only batch
authentication. Before enrollment they fail immediately rather than falling
back to keyboard-interactive and accidentally combining enrollment with token
issue. This client restriction is defense in depth; the server-side
recovery-only session gate remains authoritative.

## Failure and Security Behavior

- Reset affects only root user state and canonical root authorization.
- Old root passwords, SSH keys, and JWTs fail immediately after activation.
- No ordinary command can use the recovery password as a root login.
- Key ownership proof remains mandatory before a candidate can be selected.
- Pending keys and generations remain connection-local until `enroll-key`.
- Enrollment validates state and persists password removal plus keys in one ACL
  save transaction.
- Passwords are never accepted through Make variables, environment variables,
  command arguments, or logs other than the one intentional startup output.
- Expected recovery failures use generic authentication or command failure
  results and never expose password hashes or full candidate keys.
- Non-root authentication, enrollment, and token semantics do not change.

## Alternatives Considered

Changing only the Make targets would stop the common accidental flow but still
allow a direct SSH client to authenticate with the recovery password and run
`issue-token` in the same connection. That is insufficient as a security
boundary.

Rotating the shared JWT signing key during root reset would reliably revoke old
root tokens, but it would also revoke every other user's token and couple root
recovery to key-material rotation. A root authentication generation provides
the required targeted revocation.

Persisting the selected key during SSH authentication and rejecting the later
command would consume the password even when the client requested the wrong
operation. Deferring the ACL mutation to the dedicated `enroll-key` command
keeps the transition explicit and retryable.

## Tests

Lifecycle tests cover existing, missing, malformed, and multi-file root state;
canonical authorization repair; exact recovery credentials; strict reload;
restart behavior; old root SSH/password/JWT rejection; and unchanged non-root
authentication and JWTs.

ACL tests cover generation-aware root token issue and verification, recovery
issue refusal, atomic password-to-key replacement, generation mismatch, and a
competing reset/enrollment race.

Mina loopback tests cover recovery-only session marking, candidate proof,
pending enrollment, command gating, disconnect without mutation, successful
dedicated enrollment, password consumption, and unchanged non-root flows.

Make tests verify terminal-driven enrollment without `ORION_ROOT_PASSWORD` or
askpass and public-key-only batch token issue. An end-to-end SSH test proves the
complete reset, enrollment, reconnect, token issue, root token revocation, and
non-root continuity sequence.
