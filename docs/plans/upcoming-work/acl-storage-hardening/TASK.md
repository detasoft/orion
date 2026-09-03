# Harden ACL Storage Contracts

Status: todo

Reduce ACL storage to explicit, testable Local and native Git contracts before
the hierarchical authorization configuration work builds on it.

## Child Tasks

- [ ] [Use canonical repository names](canonical-repository-names/TASK.md)
- [ ] [Contain Local ACL paths physically](local-path-containment/TASK.md)
- [ ] [Remove unused ACL storage helpers](remove-unused-helpers/TASK.md)
- [ ] [Restore Local storage characterization](local-storage-characterization/TASK.md)
- [ ] [Enforce the saved snapshot contract](exact-snapshot-save/TASK.md)
- [ ] [Unify storage failure reporting](consistent-storage-failures/TASK.md)

## Recommended Order

1. Restore focused Local characterization tests and remove unrelated dead
   helpers.
2. Centralize repository-name parsing and harden physical Local containment.
3. Make the saved file set exact and define its publication guarantee.
4. Migrate load and save to one explicit failure model.
