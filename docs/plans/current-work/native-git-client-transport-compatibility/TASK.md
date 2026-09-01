# Add Blocking Native Git Client Transports

Status: in progress
Owner: codex, session codex-transport-4c73e1, resumed 2026-09-01 23:32 Europe/Amsterdam.
Source: converted from former root task list Next section.

Add blocking `git://`, SSH, and Smart HTTP(S) transport adapters, a URI-scheme
dispatcher, and end-to-end remote fetch/push compatibility tests after the
virtual-thread upload-pack and receive-pack client sessions land. The protocol
conversation executes on a virtual thread through blocking session boundaries;
transport internals may use their own I/O implementation. No transport or
protocol state machine is required.

An adapter is ready only when its configured connect, read, write, and whole
operation timeouts are applied or the unsupported timeout is explicitly removed
from its contract. SSH must validate known hosts strictly by default and map a
host-key rejection to `VERIFICATION_FAILED`. Smart HTTP must document its TLS,
redirect, and proxy ownership; redirect policy is deny-by-default, while proxy
and custom TLS policy are deferred to the external synchronization configuration
task unless implemented and tested here.

This is the transport prerequisite for provider-neutral external repository
synchronization, including scheduled inbound and outbound sync runs.
