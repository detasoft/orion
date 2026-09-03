# Add Hierarchical Orion Configuration

Status: active
Depends on for encrypted secret activation:
../unified-key-material-bootstrap/configuration-secret-cryptography/TASK.md

Turn the versioned `orion.xml` stored in Orion into the desired-state model for
organizations, teams, repositories, organization-local users, roles, grants,
and repository synchronization settings.

The canonical repository address is `organization/team/repository`. Runtime
queues, leases, observations, and last-run state remain outside this document.

## Child Tasks

- [ ] [Model organization-local users](organization-users/TASK.md)
- [ ] [Add scoped roles and grants](scoped-roles-and-grants/TASK.md)
- [ ] [Implement hierarchical authorization](hierarchical-authorization/TASK.md)
- [ ] [Load and activate native Git configuration snapshots](native-git-configuration-snapshots/TASK.md)
- [ ] [Add configuration administration and acceptance coverage](administration-and-acceptance/TASK.md)
