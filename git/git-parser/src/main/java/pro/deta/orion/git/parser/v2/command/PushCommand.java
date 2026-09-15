package pro.deta.orion.git.parser.v2.command;

/**
 * Owns the complete push lifecycle: quarantine, object resolution, validation, and policy-aware ref publication.
 * Creates and closes the v2 PackIngestor within execution. Reuses shared receive-pack policy validation;
 * the session and reader do not manage ingestion state or storage resources.
 *
 * Ref updates remain conditional and run after pack publication; their failure does not undo that publication.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code execute(PushRequest, BufferedByteInput)} - consume any required pack and return PushResponse.</li>
 *   <li>{@code receivePack(...)} - privately coordinate pack ingestion; the contract remains to be defined.</li>
 *   <li>{@code publish(...)} - privately validate updates and publish through the existing provider policy.</li>
 * </ul>
 * Method names and signatures are provisional. Pack input continues from the same logical input used for
 * command parsing, preserving buffered bytes. Deletion-only pushes do not require a pack; this command closes
 * its ingestion resources but does not own the transport input.
 */
public final class PushCommand {
}
