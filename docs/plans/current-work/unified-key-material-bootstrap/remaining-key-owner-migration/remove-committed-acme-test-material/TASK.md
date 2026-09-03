# Remove Committed ACME Test Material

Status: todo

Remove the private keys and certificate produced by the historical manual ACME
test before migrating production ACME ownership.

## Scope

- Delete the tracked `net/http-core/account.keypair`, `domain.keypair`, and
  `domain.crt`; never import these disclosed values into the material store.
- Remove the disabled test path that creates key and certificate files in a
  module working directory. Any retained manual scenario must require an
  explicit external output location.
- Add narrowly scoped ignore or repository checks that prevent the same ACME
  artifacts from being committed again without hiding legitimate test fixtures.
- Record that rotation and any Git-history cleanup are operational security
  decisions; current-tree deletion alone does not retract published history.
- Verify the remaining HTTP challenge and generated-certificate tests without
  using persistent source-tree material.

## Acceptance

- No tracked file under `net/http-core` contains the removed private keys.
- Routine tests cannot recreate those files in the checkout.
- HTTP challenge coverage remains active; only the unsafe manual persistence
  behavior is removed.
