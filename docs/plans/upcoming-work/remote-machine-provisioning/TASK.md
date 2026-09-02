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
  versioned `agentd` and `session-host` artifacts. Keep machine launch
  configuration in the server-side agent record.
- Use a short-lived SSH connection to verify and terminate the previous AgentD,
  pass a single-use launch permit through stdin or an anonymous channel, start
  the replacement detached from SSH, and close the SSH connection immediately.
- Do not install AgentD as a systemd, initd, launchd, or Windows service. After
  reboot or a sustained control-channel outage, Orion Server starts it again.
- Verify versioned AgentD artifacts with a server-recorded SHA-256 digest before
  launch and switch updates atomically without terminating session hosts.
- Apply the configured startup timeout, offline recovery timeout, and bounded
  retry backoff; never start a replacement unless termination of the previous
  AgentD was confirmed.
- Verify provisioning end to end through AgentD registration and a successfully
  launched session host, including safe retry after partial provisioning.
