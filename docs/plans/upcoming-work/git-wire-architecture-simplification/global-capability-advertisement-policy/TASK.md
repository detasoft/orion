# Add a Global Git Capability Advertisement Policy

Status: todo
Plan: [Git wire architecture simplification](../../../2026-09-03-git-wire-architecture-simplification.md)

Replace scattered advertisement switches with one immutable, server-wide
policy that can veto a Git capability everywhere it could be advertised.

## Scope

- Define one interface keyed by canonical capability name, with an allow-all
  default and an explicit global deny set.
- Treat the policy as a veto only: it may hide an implemented capability but
  may never enable an unsupported one.
- Apply the same policy to legacy upload-pack, legacy receive-pack, and
  protocol v2 commands and features for native TCP, SSH, and Smart HTTP.
- Match valued capabilities such as `agent=<value>` by canonical name and
  handle parent/child relationships such as `fetch` and its advertised
  features consistently.
- Derive negotiation and request validation from the effective advertised set
  so a hidden capability cannot still be selected by a client.
- Install the immutable policy once at application composition time; do not
  add runtime mutation, per-session toggles, or per-repository policy.
- Remove per-protocol booleans whose only job was deciding advertisement while
  retaining configuration that controls real implementation behavior.

## Completion Criteria

- One deny decision suppresses a capability across every applicable protocol
  and transport.
- The default policy preserves the current advertisements byte for byte.
- Tests cover a legacy capability, a valued capability, a protocol v2 command,
  and a protocol v2 child feature.
