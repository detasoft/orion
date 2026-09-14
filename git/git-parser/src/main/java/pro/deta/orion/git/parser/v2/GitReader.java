package pro.deta.orion.git.parser.v2;

/**
 * Reads Git wire requests and validates their syntax independently of repository and access-control logic.
 * Keeps pkt-line framing and payload parsing internal; reads negotiation messages individually so the server
 * can reply between reads. Pack ingestion belongs to PushCommand.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code readCommandHeader()} - read a protocol v2 command and its capabilities.</li>
 *   <li>{@code readLsRefsRequest()} - collect ls-refs arguments.</li>
 *   <li>{@code readFetchRequest()} - collect protocol v2 fetch arguments.</li>
 *   <li>{@code readLegacyUploadRequest()} - collect legacy wants and negotiated capabilities.</li>
 *   <li>{@code readNegotiationMessage()} - read one have, done, or flush message.</li>
 *   <li>{@code readPushRequest()} - read ref-update commands and capabilities before the pack body.</li>
 * </ul>
 * Method names and signatures are provisional; request values must remain independent of native storage.
 */
public final class GitReader {
}
