# Contain Local ACL Paths Physically

Status: todo

Make the configured Local ACL directory a real filesystem boundary rather
than relying only on lexical `Path.normalize()` checks.

## Scope

- Resolve and validate the Local ACL root once for the storage instance.
- Reject traversal through symbolic links in any existing intermediate path
  component and reject a symbolic link at the target file.
- Keep safe creation of missing nested directories and files inside the
  configured root.
- Minimize check/use races during save; use operations anchored to a verified
  directory or an equivalent no-follow strategy instead of treating one
  `toRealPath()` check as sufficient.
- Preserve the distinction between a missing configured ACL file and an I/O or
  containment failure.

## Completion Criteria

- Focused tests cover valid nested paths, `..` traversal, an intermediate
  symlink escaping the root, a final-file symlink, load, overwrite, and initial
  save.
- Neither load nor save can reach a file outside the configured root through a
  symlink controlled inside that root.
- Failure reporting remains compatible with the storage error-model task or
  is migrated together with it when implementation order requires that.
