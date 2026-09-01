# Add Blocking Native Git Client Transports

Status: todo
Source: converted from former root task list Next section.

Add blocking SSH and Smart HTTP transport adapters and end-to-end remote
fetch/push compatibility tests after the virtual-thread upload-pack and
receive-pack client sessions land. Each connection runs on a virtual thread and
exposes `BufferedByteInput` and `BufferedByteOutput`; no transport or protocol
state machine is required.
