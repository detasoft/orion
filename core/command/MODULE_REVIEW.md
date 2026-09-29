# Module Review: `core/command`

## 7. Tables and completion columns measure UTF-16 length instead of terminal cell width

**Problem.** `TerminalCommandRenderer` and `TerminalDisplay.columns` use `String.length()` for widths and
padding. `界界` occupies four cells with a length of two; `e\u0301` occupies one cell with a length of two.
Columns become misaligned and tables can incorrectly appear to fit the terminal.

**Sources and owners.** [Padding](src/main/java/pro/deta/orion/command/terminal/TerminalCommandRenderer.java#L31),
[fits](src/main/java/pro/deta/orion/command/terminal/TerminalCommandRenderer.java#L46),
[widths](src/main/java/pro/deta/orion/command/terminal/TerminalCommandRenderer.java#L59), and
[completion columns](src/main/java/pro/deta/orion/command/terminal/TerminalDisplay.java#L213).
Real values come from [repositoryRows/defaultHead](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/ReadOnlyDomainCommandCatalog.java#L578),
the [source](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/command/read/DefaultOperatorDomainSource.java#L35),
and [decision titles](src/main/java/pro/deta/orion/command/decision/DecisionCommandCatalog.java#L95).
[Table tests](src/test/java/pro/deta/orion/command/terminal/TerminalCommandRendererTest.java#L22) cover ASCII
and escaping.

**Documented behavior.** The [shell plan](../../docs/plans/tasks/08_interactive-ssh-shell/TASK.md#L159)
requires terminal width handling. The prompt already uses Unicode cell width.

**Contract.** Table selection and padding must use the same cell width of displayed text. Preserve the
existing `plain`, `terse`, and `json` formats.

**Minimal repair.** Reuse `\X` and [TerminalCellWidth](src/main/java/pro/deta/orion/command/terminal/TerminalCellWidth.java#L64),
summing grapheme cluster widths. Calling `width` on an entire string is incorrect: it returns the maximum
within one cluster. Measure escaped text; cover CJK, combining sequences, emoji, and plain fallback when
the terminal is too narrow.

**Alternatives and consequences.** Extending the existing mechanism locally removes two width models.
No new library is needed. Always choosing plain output removes existing table support.

**Confidence.** High from the arithmetic, output path, and existing Unicode mechanism; no terminal emulator
was run. Unicode completion candidates have limited current application reach; table values have broader
sources.

**Priority signals.** Importance: low/medium, incorrect display of supported values. Repair ease: high/medium,
two consumers and one existing mechanism.
