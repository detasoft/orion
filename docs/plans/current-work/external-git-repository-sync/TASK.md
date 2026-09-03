# Implement External Git Repository Synchronization

Status: active

Attach Orion repositories to external Git remotes through a provider-neutral
synchronization mechanism. GitHub remains a profile over that mechanism.

Operational queues, leases, observations, conflicts, and last-run state remain
outside `orion.xml`.

## Child Tasks

- [ ] [Implement primary upstream synchronization](primary-upstream/TASK.md)
- [ ] [Add configurable branch filtering](branch-filtering/TASK.md)
- [ ] Add SSH remote credentials and host verification.
- [ ] Add GitHub App credentials and webhook-triggered wakeups.
- [ ] Add outbound-only secondary remotes and tag synchronization.
