package pro.deta.orion.git.parser.v2;

/**
 * Transport carrying a Git exchange, independent of wire protocol version v0/v1/v2.
 * Legacy HTTP ends each request at the round boundary; SSH can carry subsequent rounds in the same stream.
 * Protocol v2 ends each command request at its boundary on either transport.
 */
public enum GitTransport {
    HTTP,
    SSH
}
