# Add Safe macOS AgentD Process Inspection

Status: todo

Enable fail-closed AgentD replacement recovery on macOS without trusting optional
host tools or coarse process metadata.

## Scope

- Ship and verify a native inspector/helper with the AgentD runtime.
- Prove high-resolution process birth identity and the exact executable path.
- Prove that the exact AgentD process owns the expected advisory lock.
- Integrate the proof with replacement, termination, adoption, and macOS tests.
