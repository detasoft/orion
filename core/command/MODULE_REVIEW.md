# Module Review: core/command

## 2. Redraw assumes one physical row and counts code points as display cells

**Problem.** A prompt/input longer than the terminal width wraps, but redraw clears only its current physical
line. Resizing or editing leaves stale fragments and mispositions the cursor. Wide and combining characters
also invalidate the code-point-based horizontal movement.

**Sources.** [Redraw](src/main/java/pro/deta/orion/command/terminal/TerminalDisplay.java#L33),
[width handling](src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java#L98),
[prompt redraw](src/main/java/pro/deta/orion/command/terminal/InteractiveTerminal.java#L369),
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
