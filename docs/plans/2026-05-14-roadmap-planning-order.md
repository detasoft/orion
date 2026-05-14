# Roadmap Planning Order

## Goal

Define the order for turning `ROADMAP.md` open areas into implementation plans
that can be reviewed one topic at a time.

## Discussion Order

Start from the end of the roadmap and defer the two largest areas:

1. Native Git transport migration to NIO.
2. Dynamic domain allocation.
3. Maven repository serving over HTTP and HTTPS.
4. OAuth and application-token authentication improvements.
5. Build execution and agent management.
6. GitHub and GitLab repository mirroring.

## Deferred Areas

Build execution and agent management is intentionally late because it affects
scheduling, runtime isolation, agent lifecycle, logs, cancellation, and likely a
new persistence model.

GitHub and GitLab repository mirroring is intentionally late because it depends
on stronger authentication, token handling, repository update events, conflict
rules, and possibly build execution triggers.

## Review Style

Each topic should start as a design plan under `docs/plans`. The plan should
define the goal, current state, scope, non-goals, phased implementation path,
open questions, and verification requirements before code is changed.
