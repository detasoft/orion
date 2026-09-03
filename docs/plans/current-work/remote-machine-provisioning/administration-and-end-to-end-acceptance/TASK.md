# Add Remote Provisioning Administration and Acceptance

Status: todo
Depends on: ssh-key-enrollment/TASK.md,
agent-replacement-and-recovery/TASK.md, completed AgentD runtime and packaging

Connect provisioning to durable server records, protected configuration, and
operator workflows, then prove the complete remote worker lifecycle.

## Scope

- Resolve SSH credentials from the protected material store or encrypted
  `orion.xml` values without persisting plaintext.
- Add administration flows for machine configuration, enrollment, launch, and
  actionable failures.
- Verify a real packaged AgentD registers after detached SSH launch and starts
  a packaged session host through the central-server control path.
- Cover reboot recovery, partial provisioning retry, updates, and sustained
  control-channel outages.
