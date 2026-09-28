# Module Review: `configuration-location`

## 1. Removed Git loading left two unreachable helpers

**Problem.** `ConfigurationLocationParameters.firstPresent` and `ConfigurationLocationSecret.optionalSecret`
remain after their Git reader callers were removed. Current configuration loading never invokes either method.

**Sources.** [First helper](src/main/java/pro/deta/orion/config/LocationConfigurationProvider.java#L257),
[second helper](src/main/java/pro/deta/orion/config/LocationConfigurationProvider.java#L417),
[current readers](src/main/java/pro/deta/orion/config/LocationConfigurationProvider.java#L142), and
[live S3 secret resolution](src/main/java/pro/deta/orion/config/LocationConfigurationProvider.java#L364).
Both methods and their owners are package-private; repository-wide searches found only definitions and no
reflective registration. Commit `a42752fd` removed the Git callers. The real owner is
[bootstrap](../../core/bootstrap/src/main/java/pro/deta/orion/App.java#L104).
Behavior coverage includes [lookup tests](src/test/java/pro/deta/orion/config/LocationConfigurationProviderTest.java),
[bootstrap parsing](src/test/java/pro/deta/orion/config/OrionConfigurationBootstrapShapeTest.java), and
[S3 integration](../../tests/integration-test/src/integration-test/java/pro/deta/orion/test/LocationConfigurationProviderIT.java).

**Documented behavior and contract.** [Configuration precedence](../../README.md) remains required, as do
explicit-location errors, YAML/TOML parsing, file/classpath/S3 loading and required S3 secret resolution.
No current requirement for either helper was found. The
[shared-secret plan](../../docs/plans/tasks/02_hierarchical-orion-configuration/05_secret-reference-credential-management.md)
does not require retaining these unused implementations.

**Minimal repair.** Delete only these two methods. Preserve their live enclosing helpers, especially
`requiredSecret` and `fileReference`. Use existing behavior tests and the required reactor check; add no
source or reflection assertion about absence.

**Alternatives and consequences.** Retention keeps unnecessary code. Migrating all readers to the planned
resolver would broaden the result. Local deletion changes no observable, wire or persistence contract and
requires no new API or dependency.

**Confidence.** High from callers, visibility, wiring and history; no build or tests were run in the audit.

**Priority signals.** Importance: low, no runtime failure. Repair ease: very high, two deletions in one file.
