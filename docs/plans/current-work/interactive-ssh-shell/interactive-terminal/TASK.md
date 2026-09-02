# Build the Interactive Orion Terminal

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md
Depends on: ../authentication-and-enrollment/TASK.md,
../command-core-and-exec/TASK.md

Replace the current process-backed shell path with an Orion-only PTY frontend
over the shared command dispatcher.

## Scope

- Render a prompt containing username and current resource path without ever
  starting an operating-system shell or command interpreter.
- Support absolute and relative commands, `/`, `..`, `?`, `help`, `quit`, and
  navigation through dynamic resource scopes.
- Add line editing, cursor movement, history, and Tab completion for visible
  namespaces, actions, authorized IDs and names, fields, and enum values.
- Handle PTY resize, terminal-width-aware rendering, and optional ANSI only for
  interactive sessions.
- Make `Ctrl-C` cancel the active command and return to the prompt; make
  `Ctrl-D` at the Orion prompt equivalent to `quit`.
- Test resize, completion ambiguity, history, cancellation, EOF, disconnect,
  relative navigation, and attempts to escape to system commands.
