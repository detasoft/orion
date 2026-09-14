# Add an S3 Key Material Store

Status: todo
Depends on: completed key-material rotation and recovery (406c4354),
[secret-reference management](02_hierarchical-orion-configuration/05_secret-reference-credential-management.md)

Extend the existing `KeyMaterialContentStore` boundary with an S3-compatible
writable adapter. This is a follow-up to the completed Orion-owned key migration;
it does not reintroduce private-key files or a second keystore owner. Remote
Git-backed bootstrap and material access use the existing native bootstrap runtime,
verified through native HTTP and SSH in `79dd66b4`.

## Requirements

- Support an S3-compatible writable material store for MinIO and AWS S3.
- Keep the protected `PKCS12` keystore and typed key-material capabilities as
  the only runtime key representation. Resolve store locations and credentials
  through the shared resource-reference model, without a new address parser.
- Preserve compare-and-swap writes and fail on conflicting updates. A failed
  write must not publish a partial or stale material generation.
- Never create a missing remote material store without the explicit startup
  `--create-if-missing` switch; never import legacy private-key files.

## Design and Implementation

1. Identify the existing `KeyMaterialContentStore` and resource-resolution
   contracts and select remote locations without changing local behavior.
2. Add the S3 adapter with conditional writes based on ETag or object version,
   including a defined first-write race and retryable versus terminal failures.
3. Wire the adapter at bootstrap and test restart, conflict, unavailable
   remote, and recovery behavior without cross-store atomic commits.

## Acceptance

- S3-backed keystore bytes round-trip through a fake S3 test and a MinIO
  integration test; concurrent writers cannot silently overwrite one another.
- A missing remote store fails closed by default, and explicit creation is
  tested separately. Existing key aliases and previous rotation generations
  remain physically stored unless an explicit retention policy is added.
