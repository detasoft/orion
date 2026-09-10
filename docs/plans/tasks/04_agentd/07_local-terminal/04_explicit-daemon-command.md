# Make the AgentD Daemon Command Explicit

Status: todo
Depends on: 02_attach.md

Separate server daemon startup from local terminal commands with an explicit
top-level command router.

## Requirements

- Support `agentd daemon --server ...` and keep `agentd terminal ...` isolated
  from launch-permit input and HTTP/2 construction.
- Remove the former subcommand-free server invocation in the same change.
- Update remote provisioning, tests, help, and user-facing examples to pass
  `daemon` explicitly.
- Preserve daemon parsing, permit ownership, redaction, shutdown, and exit-code
  behavior behind the new route.

## Acceptance

- Help and invalid-command paths do not consume a launch permit.
- Provisioned and direct daemon starts use the one explicit syntax, while local
  terminal mode constructs no server runtime.
