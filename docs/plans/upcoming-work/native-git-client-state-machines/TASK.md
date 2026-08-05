# Add Native Git Client State Machines

Status: todo
Source: converted from former root task list Next section.

Before remote replication work, add native upload-pack and receive-pack client
state machines on top of the Continuation-based `git-parser` wire machine. This
outbound client path is not required by the native Git server clone/push work.
