# Move ACME Keys into the Material Store

Status: todo
Depends on: [committed ACME test material cleanup](../remove-committed-acme-test-material/TASK.md)
Related plan: ../../../../2026-09-03-acme-key-material-migration.md

Make the protected material store the only runtime owner of ACME account keys,
domain private keys, and issued certificate chains.

## Scope

- Add an ACME-specific typed capability; do not inject or expose raw
  `KeyMaterialService` through the runtime component.
- Open the PKCS12 material store once and derive both server-identity and ACME
  capabilities from that owner. Do not open competing services over one file.
- Use a distinct ACME account purpose and a TLS identity descriptor for the
  domain key and issued chain, with configured aliases and versions in cluster
  scope.
- Replace `accountKeyPath`, `domainKeyPath`, and `certificatePath` with material
  references. Remove direct key and certificate file reads/writes and legacy
  runtime fallback.
- Persist a successful issued chain with its domain key in the material store;
  verify restart/reload behavior.
- Remove private-key PEM from `IssuedAcmeCertificate` and from ordinary ACME
  HTTP responses. GET and POST may return certificate chains and metadata only.
- Keep any future combined private-key export outside the issuance result and
  behind a separately reviewed, explicitly privileged material-export
  capability; do not preserve the current implicit nginx PEM download.
- Cover new-store generation, existing-store reuse, missing or mismatched
  descriptors, issuance persistence, reload, and absence of legacy files.

## Acceptance

- Production ACME code contains no filesystem path resolution or private-key
  serialization.
- The runtime component receives only the ACME purpose capability.
- Issuance and restart reuse the same material aliases and certificate chain.
- No HTTP response model contains a private key.
