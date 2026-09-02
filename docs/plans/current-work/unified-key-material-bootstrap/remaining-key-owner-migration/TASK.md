# Migrate the Remaining Orion Key Owners

Status: todo
Depends on: ../typed-material-capabilities/TASK.md,
../server-identity-migration/TASK.md

Stop Orion-owned private keys from being scattered across independent files.

## Scope

- Move SSH host keys, HTTPS private keys and chains, ACME account and domain
  keys, and future CA issuer keys behind their typed material capabilities.
- Preserve necessary import and export compatibility without retaining legacy
  files as active storage.
- Define node-local versus cluster-wide placement for every migrated purpose.
- Verify reload and rotation behavior for each consumer.
