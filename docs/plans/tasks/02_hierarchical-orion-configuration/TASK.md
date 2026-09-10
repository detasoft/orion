# Add Hierarchical Orion Configuration

Status: active
Depends on for encrypted secret activation:
completed configuration-secret-cryptography (d17476ab)

Turn the versioned `orion.xml` stored in Orion into the desired-state model for
organizations, teams, repositories, organization-local users, roles, grants,
and repository synchronization settings.

The canonical repository address is `organization/team/repository`. Runtime
queues, leases, observations, and last-run state remain outside this document.
