package pro.deta.orion.git.parser.v2.command;

/**
 * Owns the complete push lifecycle: pack ingestion, quarantine, validation, and policy-aware ref publication.
 * Creates and closes PackIngestionSession, backed by PackIngestor, within execution. Reuses existing shared
 * receive-pack validation rather than duplicating it. The session and reader do not manage ingestion state.
 *
 * <p>During an ordinary push, the pack checksum arrives in the trailer and is not known before reception.
 * Receive into an isolated temporary area identified by an upload ID, computing the checksum incrementally.
 * After complete reception and checksum verification, pass the prepared pack and verified packId to storage
 * for publication. The upload ID identifies this attempt; it is not the pack's content identity.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code execute(PushRequest, BufferedByteInput)} - consume any required pack and return PushResponse.</li>
 *   <li>{@code receivePack(...)} - privately read chunks and feed the command-owned ingestion session.</li>
 *   <li>{@code completeReceivePack(...)} - privately validate completion or report incomplete input.</li>
 *   <li>{@code publish(...)} - privately validate updates and publish through the existing provider policy.</li>
 * </ul>
 * Method names and signatures are provisional. Pack input continues from the same logical input used for
 * command parsing, preserving buffered bytes. Deletion-only pushes do not require a pack; this command closes
 * its ingestion resources but does not own the transport input.
 */
public final class PushCommand {
}
