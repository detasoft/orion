# Multiplex Native Git Side-Band Channels

Status: done
Source: converted from former root task list Next section.

Multiplex native Git side-band channels in `GitNativeClientOutput`: interleave
DATA pack production with ordered PROGRESS and ERROR messages through one
backpressured response and one outbound transport.
