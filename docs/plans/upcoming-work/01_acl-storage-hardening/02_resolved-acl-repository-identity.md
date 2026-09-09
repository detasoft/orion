# Use the Resolved ACL Repository Identity

Status: todo

Make ACL storage consume the repository identity already selected by bootstrap,
without reconstructing and reparsing a repository locator.

## Scope

- Construct native ACL storage from the existing `ResolvedBootstrapSource`
  repository name, ref, paths, and creation flag.
- Remove the ACL connector's name-to-locator round trip and repeated native
  repository-name parser without adding a compatibility constructor.
- Preserve provider-mediated reads and writes, configured-ref notifications,
  creation behavior, and filesystem-backed ACL resolution.
- Leave the broader accepted-name and transport normalization policy to the
  canonical repository-name task.

## Completion Criteria

- Encoded repository names select the same repository during bootstrap and ACL
  access, including when the decoded-looking repository also exists.
- Existing proxy-alias and native ACL storage behavior remains covered through
  the production resolver path.
- Focused ACL storage tests and development verification pass.
