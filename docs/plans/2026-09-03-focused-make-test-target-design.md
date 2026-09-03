# Focused Make Test Target Design

## Goal

Add one Make target for running a selected Maven test in a selected reactor
module without spelling out the full Maven command.

## Interface

The primary, automation-friendly form uses named Make variables:

```shell
make run-test MODULE=core/bootstrap TEST='AppTest#method'
```

A positional convenience form is also available when `run-test` is the first
goal:

```shell
make run-test core/bootstrap 'AppTest#method'
```

The target requires exactly a module and a test locator. It reports a concise
usage error when either value is missing or the positional form has extra
arguments.

## Execution

Both forms resolve to the same command:

```shell
mvn test -Pdev -T 4 -q -pl <module> -am \
  -Dtest=<test-locator> \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The positional form enables a fallback no-op rule only while `run-test` is the
first requested goal. Positional values that match another top-level Make goal
are rejected as ambiguous and must use the named form. This keeps ordinary
unknown-goal errors intact outside the shortcut.

## Agent Guidance

`AGENTS.md` directs agents to use the named-variable form for focused Maven
tests. Named variables avoid Make goal-parsing ambiguity and allow the command
to match a reusable approval rule.

## Verification

Verify argument validation without invoking Maven, then run an existing focused
test through both interfaces. Confirm that an unrelated unknown Make goal still
fails normally.
