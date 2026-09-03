# Interactive SSH Shell Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Turn Orion's existing Apache Mina SSHD endpoint into a safe operator shell and future session-host
PTY gateway while preserving Git-over-SSH behavior.

**Architecture:** Keep one SSH server with distinct Git and named-user login paths. Route shell and exec input
through a Mina-independent command tree that resolves scoped resources, applies ACL checks, records audits, and
returns structured results for terminal or automation renderers. Never start an operating-system shell.

**Tech Stack:** Java 21, Apache Mina SSHD 2.13.2, Orion ACL and resource providers, Maven integration tests,
and the existing session-host control protocol for the later PTY gateway.

---

## Non-negotiable boundaries

- Preserve `git@orion` public-key identity and existing `git-upload-pack` and `git-receive-pack` handling.
- Authenticate named users by registered keys or their Orion password; password authentication may enroll keys.
- Do not expose an operating-system shell, command escape, arbitrary process execution, or a public SSH endpoint
  on session-host.
- Keep command parsing, authorization, handlers, results, and rendering independent of Apache Mina SSHD.
- Treat command visibility as presentation only. Resolve and authorize every concrete domain resource separately.
- Keep `proxy` as the Orion remote-Git synchronization resource; do not rename it to `tunnel`.
- Make the first usable shell read-only except for management of the current user's SSH credentials.
- Audit exec and interactive commands, enrollment, and credential changes with sensitive-value redaction.

## SSH authentication model

The shared endpoint selects behavior from the SSH username and request type:

- `git@orion` continues through the Git command handler and existing key-derived identity model.
- `<user>@orion` uses named-user authentication and may request either the Orion shell or Orion command exec.
- A failed user public-key attempt may contribute a candidate key to the connection-local enrollment set, but
  must not authorize the connection.
- Enrollment proceeds only through keyboard-interactive authentication with the named Orion user's password.

The first keyboard-interactive round contains only a hidden password prompt. After successful password
verification, display deduplicated keys offered on that SSH connection and require the user to select one or more
keys or paste an OpenSSH public key. Persist selected credentials and authenticate the current SSH session so the
original shell or exec request continues without reconnecting. A valid password with no candidates authenticates
the named user directly.

Before implementation, verify Apache Mina SSHD 2.13.2 behavior for public-key authentication probes. Enrollment
should accept automatically collected keys only when the protocol proves possession of the matching private key;
if Mina cannot distinguish probes from signed attempts at the authenticator boundary, use a session/authentication
listener or restrict bootstrap enrollment to manually pasted keys until ownership can be proved.

Credential storage and fingerprints must be based on SSH key abstractions rather than an RSA/Ed25519 enum so that
OpenSSH security-key algorithms and hardware-backed credentials can be supported later.

## Command core

All frontends construct the same request and invoke one dispatcher:

```text
SSH exec -----------+
                    |
Interactive SSH ----+-> parser -> command tree -> scoped resolution
                    |                         -> ACL authorization
future adapters ----+                         -> handler -> structured result
                                                       -> audit
```

The core model must cover:

- a request context containing authenticated identity, request/session ID, source address, current resource path,
  presentation capabilities, cancellation, and audit metadata;
- absolute and relative resource paths with dynamic identifiers or unique names;
- a small action vocabulary: `ls`, `show`, `add`, `rm`, `attach`, and `monitor`;
- structured results for rows, object values, messages, streams, session attachment, and process exit;
- renderer-neutral errors for unknown commands, missing resources, ambiguous prefixes, denied access, invalid
  arguments, cancellation, and handler failure;
- explicit redaction metadata for parameters that must not enter audit records or diagnostics.

Handlers receive domain services and the command context. They must not receive Mina channel objects or write
directly to terminal streams. Resource resolution first applies the current parent scope, then matches a full ID,
an unambiguous ID prefix, or an allowed unique name. Ambiguous matches return candidates and require a longer
prefix. ACL filtering applies both while listing resources and before an action on a resolved resource.

SSH exec uses the same parser and dispatcher without a prompt, ANSI output, or interactive questions. It emits
stable plain output and maps structured completion to the SSH channel exit code. Git exec commands remain on the
existing Git path and are never interpreted as Orion commands.

## Interactive terminal

The PTY frontend maintains a current resource path and renders prompts such as:

```text
[vlad@orion] >
[vlad@orion /organization/acme] >
```

It supports `/`, `..`, `?`, `help`, `quit`, absolute and relative commands, cursor navigation, command history,
and Tab completion. Completion covers static namespaces and actions, authorized dynamic identifiers and names,
`where` fields, and known enum values. PTY resize updates terminal presentation and later becomes available to
streaming or attached-session results.

`Ctrl-C` cancels the active command or monitor and returns to the prompt. `Ctrl-D` exits from the main shell.
Terminal input is parsed only as Orion syntax; it is never passed to a system command interpreter.

## Initial command tree

The first functional shell provides:

```text
/
|-- repository
|   |-- ls
|   `-- <id-or-name> show
|-- organization
|   |-- ls
|   `-- <id-or-name>
|       |-- user ls
|       `-- repository ls
|-- session
|   |-- ls
|   `-- <id-or-prefix> show
|-- proxy
|   `-- ls
|-- auth
|   `-- key
|       |-- ls
|       |-- add
|       `-- rm <fingerprint-prefix>
|-- system
|   |-- resource
|   `-- service ls
`-- whoami
```

`/auth/key add` can select proven candidate keys from the current connection or accept a manually pasted OpenSSH
public key. Adding a key in an already authorized session does not require reconnect. Removing a credential uses
an unambiguous fingerprint prefix and must preserve a documented recovery path when the last key is removed.

## Querying and presentation

The first query grammar supports conjunctions of simple predicates after `where`:

```text
/session ls where state=running
/repository ls where organization=acme
/session ls where state!=completed
```

Do not introduce a general expression language. Later renderers add selected columns, pagination, terse output,
and JSON without changing handlers. Interactive output may use terminal-width-aware tables; no-PTY output remains
stable, plain, and free of ANSI sequences.

## Streaming and session attachment

The structured result model reserves a streaming result with cancellation, backpressure, disconnect, and channel
completion semantics. Use it later for `/audit monitor` and `/connection monitor`.

`/session/<id> attach` changes the SSH frontend into a relay state. Forward SSH PTY input, output, and resize over
the existing Orion-to-session-host control connection. A detach escape returns to the same administrative shell so
the user can attach another session. Attachment is gateway state, not a new session-host domain command; the host
only needs terminal subscription/read, input, resize, and subscription close operations. Choose the detach escape
separately and avoid consuming normal terminal control characters used by applications.

## Task order and dependencies

1. Establish named-user authentication and password-authenticated key enrollment while preserving Git behavior.
2. Introduce the transport-independent command core and SSH exec adapter.
3. Replace the current process shell with the interactive Orion PTY frontend.
4. Add authenticated credential-management commands.
5. Add ACL-filtered read-only domain commands and hierarchical scope.
6. Add filtering, column selection, pagination, terse output, and JSON.
7. Add streaming command infrastructure and initial monitor commands.
8. Add the session-host PTY gateway after server-side session control and live terminal APIs exist.
9. Complete security and end-to-end coverage across authentication, exec, PTY, streaming, and attachment.

The first five tasks form the functional MVP. Querying beyond basic command arguments, streaming, and session
attachment may ship later, but the command/result abstractions must leave room for them from the first task.

## MVP acceptance

- `ssh user@orion` opens the Orion prompt and never an operating-system shell.
- `help`, navigation, history, completion, `whoami`, repository/session/proxy listings, system visibility, and
  SSH key listing work for authorized users.
- Organization-local paths and ID-prefix resolution respect current scope and report ambiguity safely.
- Password authentication reveals candidates only after success, lets the user select proven offered keys or paste
  a key, persists the result, and continues the current authenticated connection.
- Ordinary users see only resources permitted by the existing ACL.
- `ssh user@orion /repository ls` uses the same handler as the interactive shell, has no prompt or ANSI output,
  and returns a meaningful exit code.
- Tests prove that shell and exec requests cannot invoke arbitrary operating-system commands.
- The architecture can later relay `/session/<id> attach` through the existing session-host protocol.
