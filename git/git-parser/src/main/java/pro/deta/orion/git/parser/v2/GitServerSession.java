package pro.deta.orion.git.parser.v2;

/**
 * Coordinates a server-side Git exchange using the reader, writer, and operation-specific commands.
 * Selects protocol flow and alternates negotiation reads and replies while FetchCommand owns negotiation
 * state. PushCommand owns ingestion; the transport entry point owns the connection and execution thread.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code advertise()} - obtain and write the initial advertisement.</li>
 *   <li>{@code serveCommand()} - advertise and serve a TCP or SSH Git exchange.</li>
 *   <li>{@code serveSmartHttpPost()} - serve a stateless request after separate HTTP discovery.</li>
 *   <li>{@code dispatchCommand(...)} - select refs, fetch, or push handling.</li>
 * </ul>
 * Method names and signatures are provisional; repository mutation and pack parsing stay in their owners.
 */
public final class GitServerSession {
}
