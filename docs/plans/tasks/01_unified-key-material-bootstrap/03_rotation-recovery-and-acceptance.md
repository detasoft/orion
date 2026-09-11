# Verify Rotation, Recovery, and Bootstrap Acceptance

Status: todo
Depends on: completed configuration-secret-cryptography (d17476ab),
completed remaining-key-owner-migration,
02_short-lived-jwt-rotation-and-refresh.md

Complete the unified material bootstrap with explicit rotation and recovery
semantics.

## Scope

- Implement staged add, activate, verify or decrypt-only retention, and retire
  transitions without requiring cross-store atomic commits.
- Define coordinated backup and restore of material and versioned configuration.
- Move SSH host-key creation and reissue policy out of `OrionKeyMaterial` into
  a transport-owned lifecycle; keep key material as the generic typed storage
  and capability boundary.
- Keep the last valid configuration inactive until all referenced material is
  available and validated.
- Cover lost material, stale aliases, rollback, concurrent updates, and startup
  after partial rotation.
- Verify that no active Orion private-key consumer retains an independent file.
