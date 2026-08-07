# Replace Copied Native Client Output Chunks

Status: active
Owner: codex, session codex-ea88a4d2, paused 2026-08-07 04:28 Europe/Amsterdam; next: implement ring-buffer coordinator as a separate slice.
Source: converted from former root task list Next section.
Detailed plan: ../../2026-07-30-completion-aware-native-client-output.md

Replace copied native client output chunks with the completion-aware buffering
contract from the detailed plan: land double buffering first, then add the
ring-buffer coordinator as a separate slice.
