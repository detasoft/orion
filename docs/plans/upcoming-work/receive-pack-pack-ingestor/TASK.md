# Connect Receive-Pack To PackIngestor

Status: done
Source: converted from former root task list Next section.

Connect receive-pack wire handling directly to the continuation-based
`PackIngestor`, streaming `ByteBuf` fragments into the in-memory quarantine
store and handing off the quarantine at the pack checksum checkpoint.
