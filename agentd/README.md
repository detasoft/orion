# AgentD local terminal

AgentD can start a local native session and attach the invoking terminal:

```text
java -jar agentd.jar terminal start \
  --session-host /path/to/session-host \
  --state-dir /path/to/state \
  [--session-id ID] [--cwd PATH] -- COMMAND...
```

After the native host publishes its durable session directory, `start` prints
that directory and enters the same attach path as:

```text
java -jar agentd.jar terminal attach --session-dir /path/to/session
```

Attach replays retained terminal output from the beginning and then follows new
journal records. It does not persist a cursor or acknowledge journal records,
so every fresh attach replays the retained output again.

Press `Ctrl-]` followed by `d` to detach. Press `Ctrl-]` twice to send one
literal `Ctrl-]` byte. Detaching restores the local terminal and leaves
`session-host` and its child process tree running; the printed session directory
can be attached again later.

On normal process completion the command returns the recorded child exit code
from 0 through 255. A local detach returns 0, command-line usage errors return
2, and terminal, session, journal, or native-control failures return 1.

Local terminal attachment currently requires macOS or Linux and an available
POSIX controlling terminal at `/dev/tty`. The independent `session-host`
continues to own the PTY, child processes, journal, and control execution; an
Orion server is not involved in this local path.
