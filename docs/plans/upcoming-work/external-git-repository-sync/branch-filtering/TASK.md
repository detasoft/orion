# Add Configurable Branch Filtering

Status: todo
Depends on: baseline external Git repository synchronization

Allow each remote to synchronize an intentional subset of repository branches
instead of treating every branch as eligible.

## Scope

- Add provider-neutral include and exclude rules over canonical full branch
  refs, with exclusion taking precedence and an explicit default policy.
- Apply the same selected branch set to bootstrap reconciliation, remote-tracking
  refs, conflict reporting, and outbound work creation.
- Keep tag selection separate from branch filtering and reject ambiguous,
  invalid, or escaping patterns during configuration validation.
- Reconcile the remote again after a filter change; do not implicitly delete
  local, remote, or remote-tracking refs that leave the selected set.
- Cover exact names, namespace patterns, overlapping include and exclude rules,
  an empty selection, configuration reload, and previously tracked branches.
