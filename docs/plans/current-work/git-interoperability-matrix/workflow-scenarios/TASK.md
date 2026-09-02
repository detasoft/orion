# Add the Shared Git Workflow Scenario Catalog

Status: todo

Write the reusable engine-independent workflows that form the initial Orion Git
interoperability contract.

## Scenarios

- Initial commit, first push into a provisioned empty repository, and clone.
- Clone an existing repository with multiple commits and verify its history.
- Commit and push a fast-forward update, then pull it in a second clone.
- Alternate pull, commit, and push between two clients for a full round trip.
- Create several local commits and transfer them in one push.
- Transfer nested, empty, binary, and Unicode-path files through an update.
- Create and push a second branch, then fetch and check it out independently.
- Create or update multiple refs in one push and verify all advertised tips.
- Reject a stale non-fast-forward push and preserve the winning remote state.
- Perform an incremental fetch or pull with a common commit already present.

## Scope

- Define each scenario once in the typed Java scenario API.
- Assert refs, commit ancestry, trees, modes, and content after every material
  transfer instead of checking only the current worktree.
- Use semantic rejection assertions without matching exact server messages.
- Add the missing-repository first-push workflow as an Orion-server-only
  extension, outside the symmetric interoperability catalog.
- Exclude merge, rebase, shallow clone, partial clone, authentication, and
  transport negotiation variants from the initial catalog.

## Completion Criteria

- All ten scenarios declare their required capabilities and expected terminal
  repository state.
- Scenario code contains no JGit, canonical Git process, or Orion-specific
  branching.
