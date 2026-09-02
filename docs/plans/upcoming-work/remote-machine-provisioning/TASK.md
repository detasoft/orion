# Provision Remote AgentD Machines

Status: todo
Depends on: ../../current-work/unified-key-material-bootstrap/TASK.md
Runtime components: ../../current-work/agentd/TASK.md and
../../current-work/native-session-host/TASK.md

Let Orion connect to remote machines over SSH and reconcile the runtime needed
to make them available as AgentD workers.

## Scope

- Authenticate with purpose-scoped SSH material already available to Orion.
- Let operators add SSH private keys and passphrases to the protected material
  store or keep them as encrypted secrets in `orion.xml`; never persist
  plaintext secret material in configuration.
- Accept a bootstrap password through the administration UI for one
  provisioning attempt only. Keep it in memory, never write it to the material
  store, configuration, logs, journals, or audit data, and discard it when the
  attempt finishes.
- Provide an `ssh-copy-id` equivalent that uses the one-time password or an
  existing credential only to idempotently install a selected Orion public key
  in the remote account. Preserve unrelated authorized keys and permissions,
  verify key-based authentication, then perform provisioning with the key.
- Verify remote host identity and report actionable connection,
  authentication, and privilege failures.
- Detect the target platform and architecture, then transfer compatible,
  versioned `agentd` and `session-host` artifacts and configuration.
- Idempotently install, update, start, and verify `agentd` as a system service;
  stage `session-host` for per-session launch rather than as a second daemon.
- Verify provisioning end to end through AgentD registration and a successfully
  launched session host, including safe retry after partial provisioning.
