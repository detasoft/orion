# Module Review: core/command

## 1. Navigation, help and completion bypass cancellable operation ownership

**Problem.** Navigation into a repository catalog, help and Tab completion run synchronously on the input
reader with CommandCancellation.never(). A slow repository lookup prevents that same reader from processing
Ctrl-C, unlike ordinary commands dispatched through ActiveCommand and the shared executor.

**Sources.** [Navigation](src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java#L178),
[completion and help](src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java#L270),
[repository catalog caller](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/ReadOnlyDomainCommandCatalog.java#L376),
[repository enumeration](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/DefaultOperatorDomainSource.java#L35),
and [ordinary cancellation tests](src/test/java/pro/deta/orion/command/terminal/InteractiveTerminalTest.java#L130).
The latter do not hold a navigation catalog while attempting cancellation.

**Documented behavior.** [Interactive-shell requirements](../../docs/plans/tasks/08_interactive-ssh-shell/TASK.md#L106)
include navigation, help, completion and cancellation returning to the prompt.
[OrionShell's local rule](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/OrionShell.java)
requires one session reader and command work on OrionExecutor.

**Contract.** Slow lookup work must not own the reader needed for cancellation. Preserve authorization,
serialized editor updates and prompt ownership; canceled/disconnected operations must not apply late results.

**Minimal repair.** Reuse active-operation/cancellation ownership and the existing executor for catalog work.
Propagate cancellation through the catalog boundary and serialize accepted results with terminal state.
Verify held lookups followed by Ctrl-C/disconnect, including late-result suppression.

**Alternatives and consequences.** Restricting catalogs to memory-only data removes current functionality.
A separate executor or lookup manager adds ownership without solving the problem. Moving work alone does not
bound an underlying uncancellable lookup; repair must establish its cancellation behavior.

**Confidence.** High in reader blocking; backend latency was not reproduced and excluded Git implementations
were not inspected.

**Priority signals.** Importance: medium, because a slow backend disables interactive cancellation.
Repair ease: medium-to-low due to operation/editor races and cancellation propagation.

## 2. Redraw assumes one physical row and counts code points as display cells

**Problem.** A prompt/input longer than the terminal width wraps, but redraw clears only its current physical
line. Resizing or editing leaves stale fragments and mispositions the cursor. Wide and combining characters
also invalidate the code-point-based horizontal movement.

**Sources.** [Redraw](src/main/java/pro/deta/orion/command/terminal/TerminalDisplay.java#L33),
[width handling](src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java#L98),
[prompt redraw](src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java#L351),
and [short single-row tests](src/test/java/pro/deta/orion/command/terminal/TerminalDisplayTest.java#L23).
[OrionShell](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/OrionShell.java) forwards WINCH
but the prompt renderer receives no viewport width.

**Documented behavior.** [The shell plan](../../docs/plans/tasks/08_interactive-ssh-shell/TASK.md)
promises cursor editing and resize-aware presentation.

**Contract.** Displayed text and cursor must correspond to logical editor state after typing, movement and
resize. Ordinary long input cannot depend on an undocumented one-row restriction.

**Minimal repair.** Choose a bounded horizontal input viewport, including prompt clipping, or track occupied
rows and display-cell coordinates. A single-row viewport is smaller if multiline presentation is unnecessary.
Verify observable screen state for wrapping, shrinking, wide characters and combining sequences.

**Alternatives and consequences.** Row tracking preserves complete multiline display but adds reflow state.
Horizontal clipping hides off-screen input while preserving editing. Both need cell-aware cursor placement;
the viewport behavior requires a decision before repair.

**Confidence.** High from the emitted redraw sequence; no live terminal-emulator reproduction was run.

**Priority signals.** Importance: medium, triggered by normal long input. Repair ease: medium because viewport
policy and Unicode cell handling must be established.

## 4. Fixed action vocabulary consumes valid custom-command arguments

**Problem.** `issue-launch-permit show https://example.test /tmp/agent dev` selects `show` as its action and
`/issue-launch-permit` as its path, rather than issuing a permit for valid label `show`. A later positional
value matching an action instead produces "Unexpected token before action". Quotes do not help because
tokenization removes them before action selection.

**Sources.** [Action search](src/main/java/pro/deta/orion/command/CommandLineParser.java#L85),
[fixed vocabulary](src/main/java/pro/deta/orion/command/CommandAction.java),
[real custom command](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalog.java#L118),
[label consumer](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalog.java#L216),
[identifier validation](../../agent-protocol/src/main/java/pro/deta/orion/agent/protocol/ProtocolValidation.java#L16),
[parser tests](src/test/java/pro/deta/orion/command/CommandLineParserTest.java#L32), and
[catalog tests](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalogTest.java#L99).
Current tests use noncolliding positional values.

**Documented behavior.** The registered CommandDefinition accepts four positional arguments; AgentLabel accepts
`show`, `ls` and other action words. No positional-value reservation was found.

**Contract.** Registered custom actions must remain distinct from their valid arguments. Preserve existing
root-action and relative-path forms, including `repository ls`, and existing authorization checks.

**Minimal repair.** Select the action using existing registered definitions/current scope, then stop searching
its arguments for another action. Exercise collisions at several argument positions and relative paths.

**Alternatives and consequences.** Adding each custom command to CommandAction retains duplicate declarations.
A new delimiter changes existing syntax. Reuse command metadata rather than patching forbidden label lists;
this needs a contained parser/dispatcher integration change, not a new execution framework.

**Confidence.** High in the misparse and valid consumer input; static review only.

**Priority signals.** Importance: medium, because valid launch-permit commands fail depending on their values.
Repair ease: medium because the parser currently has no registered-action context.
