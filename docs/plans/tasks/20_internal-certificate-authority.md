# Add an Internal Certificate Authority

Status: todo
Depends on: completed key-material rotation and recovery (406c4354)

Build Orion certificate issuance on the completed protected key-material
store. The store holds CA issuer keys, issuer chains, and trusted certificates;
the CA registry owns issued-certificate metadata and revocation state.

## Requirements

- Initialize a root or intermediate X.509 CA and select its issuer alias from
  key material without exposing the raw material service broadly.
- Issue from a submitted CSR or public key. Never persist a user's private key.
- Record serial number, subject, SANs, owner, expiry, status, and fingerprint
  in a separate durable registry; enforce issuance policy through ACL.
- Provide issue, renew, revoke, and list operations, CA-chain download, and
  revocation publication through a CRL. OCSP and OpenSSH certificates are later
  work, not prerequisites for this task.
- Retain old issuer keys and certificate chains for verification until issued
  certificates have expired or been revoked; do not delete them on rotation.

## Design and Implementation

1. Define issuer selection and CA initialization against the existing typed
   key-material capability, with explicit key-generation authority.
2. Add CSR/public-key validation, certificate signing, and the CA registry.
3. Add authorized administration and read-only chain/CRL delivery.
4. Cover issuer rotation, restart, serial uniqueness, denied issuance,
   revocation, and incomplete material or registry recovery.

## Acceptance

- A certificate issued from a CSR validates against the exported CA chain;
  Orion never receives or stores the subject's private key.
- Unauthorized issuance and revocation fail without changing CA state.
- Issuer rotation leaves old chains usable for verification and does not remove
  previous private keys from the material store.
- Revoked serials appear in the published CRL after restart.
