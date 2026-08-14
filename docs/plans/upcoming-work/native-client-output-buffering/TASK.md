# Replace Copied Native Client Output Chunks

Status: completed
Source: converted from former root task list Next section.
Detailed plan: ../../2026-07-30-completion-aware-native-client-output.md

Replace copied native client output chunks with the completion-aware buffering
contract: let `GitNativeClientOutput` deliver buffered bytes through write
implementations and expose `getOutput()` / `getSideBandStream()` stream
writers whose write and flush operations can return continuation yields.
