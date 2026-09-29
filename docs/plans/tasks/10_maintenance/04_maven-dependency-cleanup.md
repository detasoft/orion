# Clean redundant Maven declarations and place dependencies at their consumers

- Owner: codex, session 01a0e9f3-3074-7992-89d5-cfa3075fd72d,
  branch `codex/maven-dependency-cleanup-3074`,
  worktree `.worktrees/maven-dependency-cleanup-3074`, resumed 2026-09-29 10:41 Europe/Amsterdam.

## Requirements

- Remove explicit declarations already supplied transitively, including those
  directly used by source code, as explicitly requested by the user. Preserve
  resolved artifact identities, versions, and scopes across the reactor except
  for the explicitly requested replacement of debug Bouncy Castle artifacts.
- Replace Bouncy Castle debug artifacts with ordinary `jdk18on` artifacts at
  version 1.79. Remove exclusions introduced to keep ordinary BC artifacts out
  and obsolete commented debug alternatives. Keep one consistent BC version
  across consumers, including ACME, and avoid redundant declarations.
- Put `orion-dependency-cleanup-audit.py` in `build-tools/` and expose its report
  through `make dependency-audit`.
- Add `build-tools/orion-dependency-minimize.py` and `make dependency-minimize`.
  Analyze both reactor modules and external libraries. For A -> B -> C, remove
  B -> C and add C at its actual consumers when B does not use C. Preserve B's
  declaration when B uses C; retain a test dependency when only B's tests use C.
- Print a reviewable patch by default and write it only with `APPLY=1` or
  `--apply`. Prefer the nearest real consumer and reuse its transitive export
  for more distant consumers.
- Ignore `build-tools/__pycache__/` in `.gitignore`.
- Document in `AGENTS.md` the repository rule of minimum necessary dependency
  scope and minimum duplication: do not add a direct dependency when a
  transitive path already supplies the required version and scope, even when
  code uses it directly. Keep explicit declarations only for a demonstrated
  build/runtime contract; preserve version mediation, scope, and exclusions.
- Present one dependency diagram from base modules towards bootstrap, with
  arrows indicating which consumer depends on the preceding module.

## Design and boundaries

Use Maven's resolved dependency trees for identity, version, scope, and
transitive paths; use JDK bytecode analysis to establish actual class consumers.
Cover production and test classes, Java 21 multi-release JARs, and each module's
own production classes on its test classpath. Preserve exclusions, optional,
classifier, provided, runtime, inherited, and version mediation constraints.
Do not move declarations with ambiguous evidence. Bytecode cannot establish
reflection or resource-only runtime usage; report that limitation explicitly.

Keep Python tooling in the standard library and reuse the audit's tree/POM
helpers. Avoid runtime services, new Maven libraries, generated architecture
documents, or unrelated source refactoring. The requested diagram is a report
deliverable. Do not automatically apply the minimizer's proposals to the reactor.

## Implementation plan

1. Continue the captured dependency cleanup and tooling changes in the assigned
   directory; exclude unrelated changes from the source checkout.
2. Complete external-library relocation and classpath handling, then review the
   algorithm for scope, version, ownership, inheritance, and uncertain evidence.
3. Exercise audit and minimizer through generated Maven fixtures and real Java
   bytecode/JARs. Cover used and unused intermediate modules, test-only usage,
   closest consumers, and patch preview/application.
4. Run the affected Make goals and compare resolved reactor dependencies before
   and after the declaration cleanup. Verify the full project with `make test`.
5. Produce the single reversed module diagram and report actual verification
   and limitations.

## Dependencies

No unfinished task is required. This maintenance task does not implement the
separate test-runtime optimization or product architecture review tasks.

## Acceptance

- Cleanup preserves the effective dependency sets, versions, and scopes of all
  reactor modules while reducing redundant declarations, except for the
  explicitly requested debug-to-ordinary BC replacement.
- Both scripts and their documented Make goals work on the actual reactor.
- External and internal dependency relocation is covered by behavioral tests;
  the default command previews a valid patch and explicit application writes it.
- Preserve effective dependency exclusions and inherited Java source roots;
  cover the two reproduced review findings before approving the minimizer.
- The resolved graph contains ordinary BC 1.79 without debug artifacts or
  unnecessary BC exclusions; cryptographic behavior remains covered by tests.
- All required checks pass and the dependency diagram reflects the resulting
  POM declarations.
