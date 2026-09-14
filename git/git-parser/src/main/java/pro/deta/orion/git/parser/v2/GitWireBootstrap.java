package pro.deta.orion.git.parser.v2;

/**
 * Parses the initial service, repository path, host, and protocol-version parameters for a Git exchange.
 * Produces session-opening data; transport entry points assemble the reader, writer, context, and session.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code nativeDaemon(...)} - parse the native Git daemon's initial request packet.</li>
 *   <li>{@code sshCommand(...)} - parse the SSH command line and Git protocol parameters.</li>
 *   <li>{@code smartHttp(...)} - interpret HTTP service, repository, and Git-Protocol parameters.</li>
 * </ul>
 * Method names and signatures are provisional; bootstrap does not resolve repositories or execute commands.
 */
public final class GitWireBootstrap {
}
