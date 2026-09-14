# Complete Bootstrap Proxy Runtime Boundaries

Status: foundation integrated; remaining boundaries and acceptance pending

- Owner: codex, session 01a09f53-ace6-7771-bae0-ceb63ee2e54e, started 2026-09-14 11:56 Europe/Amsterdam.

## Current evidence

The foundation is integrated as `e7eac547`; execution was transferred to the
current session with user authorization. Revalidate the remaining result
against current code rather than replaying the old branch implementation plan.
`BootstrapContext`, `OrionKeyMaterialFactory`, proxy-bound native handles, and
bootstrap tests already exist. Material creation is explicit in current startup.

## Requirements and design

- Preserve `bootstrap.accessControl` and `bootstrap.keyMaterial`, independent
  ref/path/auth, typed capabilities, and explicit material-creation policy.
- Preserve direct `local:` and protected direct-file inputs; all existing
  `git+ssh/http/https/file` bootstrap schemes remain supported.
- Resolve both inputs before runtime construction. ACL consumes the resolved
  configuration source without upstream or credential ownership.
- Reuse one provider for bootstrap, ACL, material, and HTTP/SSH consumers.
- Canonical upstream/ref identity shares one provisional binding across paths;
  incompatible authentication for that identity fails without replacement.
- Public Git access uses an authorized stable alias. Internal cache names are
  never routable or enumerable, even when a binding is inactive or unadopted.
- Keep refresh and expected-ID publication on every supported mutation path;
  raw mutable handles must not bypass policy.
- Failed source resolution or validation leaves no active runtime candidate;
  close owned resources and clear owned secret buffers on all exit paths.

## Dependencies

Existing native storage/client and typed-material foundations are available.
Persistent catalog activation and adoption are owned by
[06/02](02_proxy-config-and-secrets.md); this leaf owns bootstrap isolation and
handle policy, not a second catalog or asynchronous mirror worker.

## Implementation plan

1. Revalidate the current bootstrap source, lifecycle, and provider paths against
   the requirements; retain behavior already covered by meaningful tests.
2. Complete internal-cache isolation at the existing provider/transport boundary
   without exposing proxy registries to ACL, material, or route consumers.
3. Cover missing inputs, stale upstreams, conflicting credentials, retained
   handles, direct files, and normal/failed shutdown through public contracts.
4. Verify the integrated behavior through native HTTP/SSH routes as well as
   provider tests; retain streaming pack and gzip behavior.

## Acceptance

Configuration/material can share or use independent upstreams; local locations
create no proxy. Missing inputs, invalid credentials/passwords, or upstream
failure prevent public startup. Reads refresh, writes use upstream CAS, and
neither cache-name guessing nor retained handles bypass policy. Verify focused
bootstrap, proxy, ACL, material, and transport scenarios and the full JVM suite.
