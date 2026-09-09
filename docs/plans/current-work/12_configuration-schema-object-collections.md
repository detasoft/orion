# Correct HTTP Configuration Schema Object Collections

Status: in progress
Source: finding 6 in [the module review](../../../net/http-core/MODULE_REVIEW.md)

- [ ] Correct supported object collections in the published HTTP schema.
  - Owner: codex, session http-schema-6f2c, started 2026-09-09 18:51 Europe/Amsterdam.

Preserve generic collection element types in the existing HTTP configuration
schema generator so supported object collections are not advertised as strings.

## Scope

- Generate object item schemas for configured object collections, including
  server-signing verification key references.
- Add representative conformance coverage against configuration accepted by
  the real loader.
- Keep the HTTP schema endpoint, Draft 2020-12 declaration, and current
  configuration model unchanged.

## Completion Criteria

- The published schema accepts the supported verification object list and
  retains string items for string collections.
- Focused HTTP schema tests and routine development verification pass.
- No new schema framework, public configuration type, or duplicate
  configuration representation is introduced.
