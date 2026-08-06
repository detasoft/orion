# Add Delta Pack Optimization

Status: todo
Source: follow-up from production pack building.

Add server-side delta pack optimization for fetch/clone responses after the
safe no-delta production pack path.

## Scope

- [ ] Plan and emit non-thin delta entries only when the client advertises the
  required capabilities.
- [ ] Keep whole-object output as the fallback when delta generation is unsafe
  or not useful.
- [ ] Verify generated delta packs through Orion ingestion and Git/JGit
  compatibility checks.
