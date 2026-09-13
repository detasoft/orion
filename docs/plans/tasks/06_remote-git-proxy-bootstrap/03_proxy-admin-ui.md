# Administer Remote Proxy Aliases

Status: todo
Depends on: [06/02](02_proxy-config-and-secrets.md).

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

## Implementation plan

1. Add a safe projection and authorized commands over the existing catalog and
   configuration update path; do not create a UI-owned registry.
2. Add the Remote aliases section and credential replacement/retry interactions.
3. Verify unauthorized and cross-scope access, redaction, save conflicts, and
   reload of the persisted result through actual API/UI contracts.

## Acceptance

An operator can identify and administer an alias without seeing its cache or
credential contents. Retrying reports the new result. Read-only users cannot
mutate configuration. Verify API/UI behavior, negative authorization cases,
and the full JVM suite when implementation inputs change.
