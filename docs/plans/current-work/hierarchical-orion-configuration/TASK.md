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

- [ ] [Define hierarchy and stable identifiers](hierarchy-and-identifiers/TASK.md)
- [ ] [Introduce the versioned Orion XML schema](xml-schema-v2/TASK.md)
- [ ] [Model organization-local users](organization-users/TASK.md)
- [ ] [Add scoped roles and grants](scoped-roles-and-grants/TASK.md)
- [ ] [Implement hierarchical authorization](hierarchical-authorization/TASK.md)
- [ ] [Add repository and mirror configuration](repository-and-mirror-configuration/TASK.md)
- [ ] [Load and activate native Git configuration snapshots](native-git-configuration-snapshots/TASK.md)
- [ ] [Add configuration administration and acceptance coverage](administration-and-acceptance/TASK.md)
