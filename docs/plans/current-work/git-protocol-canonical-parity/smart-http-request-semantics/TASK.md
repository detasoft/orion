# Align Smart HTTP Request Semantics

Status: done
Source: 2026-09-01 canonical parity audit against `http-backend.c` and the HTTP
protocol.

Validate RPC content types with canonical exact matching and return 415 for an
unsupported media type instead of 400. Emit the complete no-cache response
headers used for discovery and RPC results.

Align endpoint-specific method handling, including HEAD as GET where
applicable, 405 responses, and accurate Allow headers. Cover wrong methods,
missing and parameterized content types, case changes, discovery for v0/v1/v2,
and upload-pack and receive-pack POST responses.

Completed 2026-09-01: RPC content types use exact matching and 415 responses,
Smart HTTP responses carry canonical no-cache headers, and routing implements
endpoint-specific HEAD and 405/Allow behavior.
