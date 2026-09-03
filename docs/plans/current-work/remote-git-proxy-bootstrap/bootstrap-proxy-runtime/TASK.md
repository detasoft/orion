# Add the Pre-Bootstrap Remote Git Proxy Runtime

Status: in progress on feature branch

- Owner: codex, session bpr-7f31c2, started 2026-09-02 17:32 Europe/Amsterdam.

Resolve process-configured bootstrap sources through provisional local native
repositories before `orion.xml` and protected material are loaded.

## Scope

- Read the `orion.xml` location, ref, and path, plus the independent material
  source descriptor, from process TOML/properties.
- Recognize `git+ssh`, `git+http`, `git+https`, and `git+file` as remote source
  bindings while leaving `local:` locations direct.
- Resolve each source credential and the material-store password from external
  `env:` or `file:` bootstrap references on every launch.
- Initialize a proxy-aware native repository provider in bootstrap mode with a
  bounded set of provisional bindings, then activate its full repository
  catalog after the material/configuration barrier.
- Keep ACL inactive until `orion.xml` has been loaded through the native facade
  and the complete runtime candidate has been validated.
- Serve the proxy alias through ordinary Orion Git HTTP and SSH routes, with
  refresh-before-read and upstream compare-and-set publication on writes.
- Keep ACL, material, configuration, HTTP, and SSH consumers unaware of proxy
  registries and prevent access through raw handles that bypass proxy policy.
- Fail startup before public transports start when either bootstrap source,
  credential, selected revision, or required path is unavailable.
- Keep bootstrap secrets out of URIs, logs, persisted proxy metadata, and
  exception messages, and clear owned secret buffers after handoff.
