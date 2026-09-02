# Restore the Internal Configuration Repository

Status: todo

Restore the versioned internal repository as Orion's bootstrap and live ACL
configuration source. Its repository name, configuration ref, and `orion.xml`
path must come from YAML or TOML configuration.

## Scope

- Resolve or create the configured native internal repository before starting
  ACL-dependent services or public transports.
- When the repository or configured ref is empty, generate the initial
  `orion.xml`, create the `root` credential, commit the file, and reveal the
  generated root password exactly once.
- Load the newly committed configuration immediately and do not report the
  server as running until the ACL snapshot is active.
- On restart, reuse the existing repository and ACL without regenerating the
  root credential or replacing user changes.
- Reload ACL from every accepted commit or push that updates the configured
  configuration ref, regardless of which Git transport accepted the update.
- Validate and activate each commit atomically; reject or retain the last valid
  ACL when a candidate snapshot cannot be parsed or applied.
- Expose the internal repository through repository discovery so the Admin API
  and UI show it alongside other repositories.
- Cover empty bootstrap, existing restart, invalid configuration, concurrent
  ref updates, and push-triggered reload end to end.

## Follow-up

The hierarchical configuration work extends this restored repository from the
current ACL document to the complete desired-state snapshot model.
