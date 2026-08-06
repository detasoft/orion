# Build Production Pack Files

Status: done
Source: converted from former production repository backends child task.

- [x] Build production pack files for server-side fetch/clone responses.

Server-side fetch/clone now builds a generated no-delta pack from selected
repository objects. Thin-pack and ofs-delta requests use the safe whole-object
policy until delta/thin-pack optimization is implemented.
