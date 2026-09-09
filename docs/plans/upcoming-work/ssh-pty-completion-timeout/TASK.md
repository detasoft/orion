# Fix the SSH PTY Completion Integration Timeout

Status: todo

Observed during `mvn verify -Pdev -T 4` for HTTP/2 control transport
(`8e00fb1c`): `GitSshTransportEndToEndIT` fails in
`interactivePtyEditsCompletesAndNeverStartsAnOperatingSystemShell` with
`Timed out waiting for terminal output: orion`.
The failing assertion follows a PTY resize to 20 columns and `repo<TAB><CR>`;
it expects `orion\n` (line 737 at the observed revision).

## Scope

- Reproduce and distinguish a terminal completion/rendering defect from stale
  output expectations or a synchronization race in the integration fixture.
- Coordinate with `current-work/interactive-ssh-shell/terminal-administration`
  and recheck its result before making overlapping terminal changes.
- Fix the smallest responsible path while preserving command completion,
  editing, history, resize handling, and the prohibition on OS shell execution.
  Do not hide the failure by skipping the test or merely increasing its timeout.

## Acceptance

- The full interactive scenario completes with the expected command result
  after resize and completion; relevant security assertions remain effective.
- Verify the scenario both in isolation and during `mvn verify -Pdev -T 4`,
  including repeated execution if the failure is timing-dependent.
- Report remaining unrelated verification failures separately.
