# Administer Remote Proxy Aliases

Status: todo
Owner: codex, session 01a09f53-ace6-7771-bae0-ceb63ee2e54e, started 2026-09-15 08:44 Europe/Amsterdam.
Depends on: completed persistent proxy bindings and shared stored credentials (`964cd7a6`, `8750c1ee`).

## Requirements and design

Expose stable remote aliases separately from ordinary local repositories.
Return owning scope, alias, sanitized upstream, transport, internal reference,
and current safe sync result. Do not return private cache identities, raw or
encrypted credentials, or material details. An internal reference must not be
presented as a Git clone URL; show an authorized alias endpoint separately when
available.

Use existing admin API authorization and UI conventions. The Remote aliases
section supports add/update, explicit credential replacement, and retry, with
observable success, unavailable, authentication-failed, and conflict results.
Audit mutations through the existing audit mechanism. Show proxy write-through
behavior distinctly from the asynchronous repository mirror configured by
[06/07](07_github-mirror-setup.md).

Use `OrionDocument.system().proxies()` as the binding authority and
`ConfigurationSecrets` for credential creation and replacement. The current
binding model is system-owned; application-admin authorization is required,
and organization or repository permissions must not grant access to these
system bindings. Do not add another binding registry or scope model.

Keep the latest synchronization observation in the proxy runtime, outside XML.
Preserve safe typed Git-client failure categories without exposing exception
messages, upstream response bodies, or credential metadata. Reading the list
must not initiate upstream I/O; explicit retry performs a fresh attempt and
returns its result. Configuration-save conflicts and upstream synchronization
results must remain distinguishable.

Persist mutations through the existing configuration storage with the revision
the operator read, preserving other configuration files and metadata. A stale
revision must not overwrite another operator's changes. Credential replacement
is explicit and write-only; ordinary metadata edits retain the existing secret.
Reload the saved document and apply bindings through the existing proxy runtime.

Present Remote aliases as a separate UI section with system scope, alias,
sanitized upstream, transport, selected ref, and synchronization observation.
The current runtime has no public alias endpoint; report its absence explicitly
and never derive a clone URL from the internal reference or cache identity.

## Implementation plan

1. Expose safe runtime observations and an authorized alias-list API, and show
   that list in the Remote aliases section. Verify system-scope authorization,
   redaction, and empty/unavailable UI states.
2. Add authorized create/update, explicit credential replacement, and retry
   through the existing configuration, runtime, and audit owners. Verify
   revision conflicts, failed authentication, recovery, and persisted reload.
3. Connect the UI forms and retry interactions to those operations, and verify
   the complete API/UI journey, including denied and cross-scope access,
   secret redaction, stale saves, and reload of the persisted result.

## Acceptance

An operator can identify and administer an alias without seeing its cache or
credential contents. Retrying reports the new result. Read-only users cannot
mutate configuration. Verify API/UI behavior, negative authorization cases,
and the full JVM suite when implementation inputs change.
