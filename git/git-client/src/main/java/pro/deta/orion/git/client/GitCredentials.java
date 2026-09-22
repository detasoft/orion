package pro.deta.orion.git.client;

import pro.deta.orion.schema.orion.GitCredentialKind;

import java.util.Arrays;
import java.util.Objects;

/**
 * Credentials shared by Git transports. The owner closes this object after the operation or connection;
 * transports receive temporary character copies and clear them after use. URI schemes select transports,
 * while the credential kind only determines how the selected transport interprets the secret.
 */
public final class GitCredentials implements AutoCloseable {
    private final GitCredentialKind kind;
    private final String username;
    private final char[] characters;
    private boolean closed;

    public GitCredentials(GitCredentialKind kind, String username, char[] characters) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.username = Objects.requireNonNull(username, "username");
        Objects.requireNonNull(characters, "characters");
        if ((kind == GitCredentialKind.NONE && characters.length != 0)
                || ((kind == GitCredentialKind.TOKEN || kind == GitCredentialKind.PRIVATE_KEY)
                && characters.length == 0)) {
            throw new IllegalArgumentException("Credential kind requires a matching secret");
        }
        this.characters = characters.clone();
    }

    public static GitCredentials none() {
        return new GitCredentials(GitCredentialKind.NONE, "", new char[0]);
    }

    public GitCredentialKind kind() {
        return kind;
    }

    public String username() {
        return username;
    }

    public synchronized char[] copyCharacters() {
        requireOpen();
        return characters.clone();
    }

    synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Git credentials are closed");
        }
    }

    void requireKind(GitCredentialKind... supported) throws GitClientTransportException {
        requireOpen();
        for (GitCredentialKind candidate : supported) {
            if (kind == candidate) {
                return;
            }
        }
        throw new GitClientTransportException(GitClientFailure.Kind.PROTOCOL_UNSUPPORTED, false,
                "Credential kind is not supported by the selected Git transport");
    }

    @Override
    public synchronized void close() {
        Arrays.fill(characters, '\0');
        closed = true;
    }
}
