use std::fs;
use std::path::PathBuf;

use orion_session_host::protocol::protocol_fixture;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let directory = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("protocol")
        .join("fixtures");
    fs::create_dir_all(&directory)?;
    fs::write(
        directory.join("session-events-v1.hex"),
        protocol_fixture::journal_hex(),
    )?;
    fs::write(
        directory.join("process-control-events-v1.hex"),
        protocol_fixture::process_control_events_hex(),
    )?;
    fs::write(
        directory.join("session-event-unknown-tail-v1.hex"),
        protocol_fixture::unknown_event_hex(),
    )?;
    fs::write(
        directory.join("control-source-aware.bin"),
        protocol_fixture::control_source_aware(),
    )?;
    fs::write(
        directory.join("command-events-v1.hex"),
        protocol_fixture::command_events_hex(),
    )?;
    fs::write(
        directory.join("start-outcomes-v1.hex"),
        protocol_fixture::start_outcomes_hex(),
    )?;
    fs::write(
        directory.join("truncated-item-v1.hex"),
        protocol_fixture::truncated_item_hex(),
    )?;
    Ok(())
}
