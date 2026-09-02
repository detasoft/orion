# Add the Pre-Bootstrap Remote Git Proxy Runtime

Status: todo

Turn a direct remote Git configuration location into a provisional local
native repository before `orion.xml` and its colocated key store are loaded.

## Scope

- Recognize `git+ssh`, `git+http`, `git+https`, and `git+file` as remote proxy
  sources while leaving `local:` locations direct.
- Resolve the remote credential and key-store password from external `env:` or
  `file:` bootstrap references on every launch.
- Start and populate the proxy before configuration and material bootstrap,
  then transfer ownership to the normal runtime lifecycle.
- Fail startup before public transports start when the upstream, credential,
  key store, or required configuration files are unavailable.
- Keep bootstrap secrets out of URIs, logs, persisted proxy metadata, and
  exception messages, and clear owned secret buffers after handoff.
