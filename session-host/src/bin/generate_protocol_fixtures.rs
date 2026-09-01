use std::fs;
use std::path::PathBuf;

use orion_session_host::protocol::protocol_fixture;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let directory = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("protocol")
        .join("fixtures");
    fs::create_dir_all(&directory)?;
    fs::write(directory.join("journal-v1.bin"), protocol_fixture::journal())?;
    fs::write(directory.join("control-v1.bin"), protocol_fixture::control())?;
    fs::write(
        directory.join("truncated-record-v1.bin"),
        protocol_fixture::truncated_record(),
    )?;
    fs::write(
        directory.join("truncated-zstd-block-v1.bin"),
        protocol_fixture::truncated_zstd_block(),
    )?;
    Ok(())
}
