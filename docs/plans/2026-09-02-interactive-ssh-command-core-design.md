# Interactive SSH Command Core Design

## Goal

Introduce a transport-independent command pipeline for Orion operator commands and route non-Git SSH exec
requests through it. Git protocol commands remain on the existing Git path. The design establishes reusable
contracts for the later interactive terminal, domain commands, renderers, monitors, and session attachment
without implementing those features in this task.

## Module boundary

Create a new `core/command` Maven module. It depends on `core/authorization` for authenticated Orion identity
and ACL integration, but it has no Apache Mina SSHD dependency. `net/git-transport` depends on `core/command`
and owns the Mina adapter, SSH stream handling, and construction of SSH request metadata.

This boundary makes the parser, dispatcher, result model, scoped resolution, cancellation, redaction, and audit
contracts reusable by interactive SSH and future local or Make adapters. It also prevents Mina channel objects
and terminal streams from entering command handlers.

## Core model

`CommandRequest` contains the raw command line and a `CommandContext`. The context carries the authenticated
`SecurityContext`, request and session identifiers, source address, current resource path, presentation
capabilities, cancellation signal, and audit metadata. Every collection is immutable and every required field is
validated at construction.

`CommandPath` represents normalized absolute or relative path segments. `ParsedCommand` separates the resolved
path, action, positional arguments, named parameters, and optional `where` predicates. Named parameters carry a
redaction flag supplied by the matched command definition; secrets are never copied into audit records or error
details.

The result model is a sealed hierarchy covering messages, tabular rows, object values, stream reservations,
session-attachment reservations, explicit process exits, and structured failures. This task renders finite
results only. Stream and attachment results are contracts for later tasks and return a stable unsupported result
if an SSH exec request reaches them before their adapters exist.

## Command grammar

The parser accepts a deterministic Orion grammar, not a system-shell grammar:

```text
[path] [action] [positional ...] [name=value ...] [where field=value|field!=value ...]
```

Paths may be absolute or relative to the context path. The supported action vocabulary is `ls`, `show`, `add`,
`rm`, `attach`, and `monitor`, while a command definition may also expose a short root action such as `whoami`.
Whitespace separates tokens; quoted strings and backslash escapes are supported only to preserve spaces and
literal quote characters. Pipes, redirects, substitutions, globbing, environment expansion, and command
separators have no special meaning.

Parsing reports structured invalid-syntax and invalid-argument failures with the token position where possible.
The `where` clause is reserved and accepts only conjunctions of equality or inequality predicates. General
expressions are outside this task.

## Command tree and dispatch

The command tree contains immutable static nodes, optional scoped-resource resolvers, and action definitions.
Each action definition owns its handler, argument contract, sensitive parameter names, visibility predicate, and
authorization check. Visibility controls help and completion only; dispatch always repeats authorization.

Dispatch proceeds as follows:

1. Reject a cancelled request.
2. Parse the raw input relative to the context path.
3. Walk static nodes and scoped dynamic nodes.
4. Resolve each dynamic resource and authorize the concrete match.
5. Match and validate the action definition.
6. Invoke the handler without exposing transport objects.
7. Convert expected failures and unexpected handler exceptions into structured results.
8. Record one redacted audit event with duration and outcome.

Existing non-Git SSH commands are registered as compatibility aliases over command handlers rather than parsed
inside `SshCommandFactory`. Their output and exit behavior remain stable. Canonical hierarchical domain commands
will be registered by later tasks.

## Scoped resource resolution and ACL

The core provides an algorithm over resource candidates containing a full identifier and an optional allowed
display name. Resolution checks, in order, an exact full identifier, a unique identifier prefix, and an allowed
unique name. Missing and ambiguous matches are returned as structured results. Ambiguous results contain safe
candidate identifiers but do not expose resources denied by authorization.

The same authorization predicate is used to filter list results and to authorize a resolved resource before its
action runs. The core owns the algorithm and contracts; concrete repository, organization, session, and proxy
resolvers are deferred to the read-only domain command task.

## Audit and redaction

`CommandDispatcher` is wrapped by an auditing dispatcher. `CommandAuditSink` receives an immutable record with
user identity, request and session identifiers, source, normalized command path, redacted parameters, result
kind and code, and elapsed duration. Audit sink failures are logged but do not replace the command result.

The SSH integration provides a structured logging sink initially. The sink is an interface so durable audit
storage can be added independently. Raw command lines are not logged because they may contain sensitive values.

## SSH exec adapter

`SshCommandFactory` keeps the existing exact Git command selection and Git wire handler unchanged. Every other
exec request is converted to a `CommandRequest`, dispatched asynchronously with the existing `OrionExecutor`,
rendered as stable UTF-8 plain output without ANSI sequences, and completed with a documented exit code.

The adapter obtains the authenticated `UserIdentity` from the existing session attribute and creates the
`SecurityContext`. Missing identity, denial, malformed input, unknown command, cancellation, handler failure,
and executor saturation each produce stable errors and exit codes. Output write failures close the command but
do not escape as expected control flow.

## Verification

The new module has focused tests for parsing, quoting boundaries, path normalization, named parameters, `where`
predicates, scoped resolution, ambiguity, per-resource denial, list filtering, cancellation, redaction, audit
outcomes, handler failure conversion, and renderer independence.

`git-transport` tests cover compatibility aliases, stable output and exit codes, authenticated context metadata,
unknown commands, denied commands, executor rejection, and unchanged routing of Git commands. A real SSH test
proves that a non-Git exec request reaches the dispatcher while `git-upload-pack` still reaches the Git handler.

## Out of scope

- The interactive PTY terminal, prompt, history, and completion.
- Concrete repository, organization, session, proxy, or system command trees beyond compatibility handlers.
- Credential-management commands.
- JSON, terse, pagination, and terminal-aware renderers.
- Streaming monitors and session attachment.
- Any operating-system command execution.
