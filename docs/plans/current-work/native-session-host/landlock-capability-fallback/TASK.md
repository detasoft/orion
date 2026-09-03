# Run Without Restrictions When Landlock Is Unavailable

Status: active
Design: ../../../2026-09-04-landlock-capability-fallback-design.md
Plan: ../../../2026-09-04-landlock-capability-fallback.md

Allow a build session to start with a warning when its machine cannot provide
Landlock ABI 9, while keeping invalid policies and rule-application failures
fatal.

- [ ] Remove the configurable unavailable-policy mode from AgentD and the
  native host CLI.
- [ ] Preserve fail-closed handling after Landlock compatibility succeeds.
- [ ] Cover unsupported-machine fallback and policy failures on Linux.
