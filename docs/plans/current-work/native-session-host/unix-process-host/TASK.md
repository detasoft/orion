# Implement the Unix PTY Process Host

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: completed contracts and build, completed journal core

Run and supervise an interactive child through a real PTY on Linux and macOS.

## Scope

- Attach child stdin, stdout, and stderr to the PTY slave, configure the initial
  size and terminal environment, and preserve PTY master output byte-for-byte.
- Record process start and exit, including exit code and signal where available.
- Serve the platform-neutral control protocol over a Unix domain socket.
- Implement input-ID deduplication, resize ordering, signal, terminate, and
  status commands with defined accepted, duplicate, and error responses.
- Ensure the host and child remain alive when the launching `agentd` exits and
  finish the journal cleanly when the child terminates.
- Test TTY detection, raw ANSI and invalid UTF-8 bytes, command retries,
  reconnects, resize/event order, signals, launch-parent loss, and shutdown.
