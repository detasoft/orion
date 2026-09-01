# Session Host Instructions

## Current Platform Limit

The root Maven build currently does not run on Windows when `session-host` is
included. `make/session-host.mk` supports only Darwin and Linux Rust bootstrap
hosts and exits before invoking Cargo for Windows host names.

Do not describe Windows as supported, enable a Windows CI job, or change the
six-target release requirement based on this limitation. A dedicated Windows
bootstrap and ConPTY task must remove this limitation before Windows builds are
enabled.
