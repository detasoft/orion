package pro.deta.orion.git.proxy;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitFileClientTransport;
import pro.deta.orion.git.client.GitHttpRequestConfigurer;
import pro.deta.orion.git.client.GitSmartHttpClientTransport;
import pro.deta.orion.git.client.GitSshClientTransport;
import pro.deta.orion.git.client.GitSshSessionAuthenticator;

import java.io.CharArrayReader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

final class BootstrapGitTransportFactory {
    private static final GitClientOptions OPTIONS = GitClientOptions.defaults();

    private final BootstrapSecretResolver secretResolver;

    BootstrapGitTransportFactory(BootstrapSecretResolver secretResolver) {
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver");
    }

    <T> T withTransport(
            BootstrapGitLocation location,
            TransportOperation<T> operation) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(operation, "operation");
        if (location.credentialKind() == BootstrapGitCredentialKind.NONE) {
            return operation.run(new GitFileClientTransport());
        }
        try (BootstrapSecret secret = secretResolver.resolve(
                "Remote Git credential",
                location.credentialReference())) {
            char[] characters = secret.copy();
            GitClientTransport transport = null;
            try {
                transport = transport(location, characters);
                return operation.run(transport);
            } finally {
                try {
                    close(transport);
                } finally {
                    Arrays.fill(characters, '\0');
                }
            }
        }
    }

    private static GitClientTransport transport(
            BootstrapGitLocation location,
            char[] credential) {
        return switch (location.credentialKind()) {
            case HTTP_BEARER, HTTP_BASIC -> httpTransport(location, credential);
            case SSH_PASSWORD, SSH_PRIVATE_KEY -> sshTransport(location, credential);
            case NONE -> new GitFileClientTransport();
        };
    }

    private static GitClientTransport httpTransport(
            BootstrapGitLocation location,
            char[] credential) {
        GitHttpRequestConfigurer authentication = request -> request.header(
                "Authorization",
                authorization(location, credential));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(OPTIONS.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new GitSmartHttpClientTransport(
                client,
                authentication,
                "http".equals(location.remoteUri().getScheme()));
    }

    private static String authorization(
            BootstrapGitLocation location,
            char[] credential) {
        if (location.credentialKind() == BootstrapGitCredentialKind.HTTP_BEARER) {
            return "Bearer " + new String(credential);
        }
        char[] combined = new char[
                location.credentialUsername().length() + credential.length + 1];
        byte[] encoded = null;
        try {
            location.credentialUsername().getChars(
                    0,
                    location.credentialUsername().length(),
                    combined,
                    0);
            combined[location.credentialUsername().length()] = ':';
            System.arraycopy(
                    credential,
                    0,
                    combined,
                    location.credentialUsername().length() + 1,
                    credential.length);
            ByteBuffer utf8 = StandardCharsets.UTF_8.encode(CharBuffer.wrap(combined));
            byte[] bytes = new byte[utf8.remaining()];
            utf8.get(bytes);
            encoded = Base64.getEncoder().encode(bytes);
            Arrays.fill(bytes, (byte) 0);
            return "Basic " + new String(encoded, StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(combined, '\0');
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private static GitClientTransport sshTransport(
            BootstrapGitLocation location,
            char[] credential) {
        if (location.knownHosts() == null) {
            throw new BootstrapGitProxyException("SSH host-key configuration");
        }
        requireProtectedKnownHosts(location.knownHosts());
        GitSshSessionAuthenticator authenticator = switch (location.credentialKind()) {
            case SSH_PASSWORD -> GitSshSessionAuthenticator.password(new String(credential));
            case SSH_PRIVATE_KEY -> GitSshSessionAuthenticator.publicKey(parseKeyPair(credential));
            default -> throw new BootstrapGitProxyException("transport selection");
        };
        return GitSshClientTransport.strictKnownHosts(location.knownHosts(), authenticator);
    }

    private static void requireProtectedKnownHosts(Path knownHosts) {
        try {
            BootstrapSecretResolver.requireIntegrityProtectedFile(knownHosts, "SSH known-hosts");
        } catch (IOException | RuntimeException error) {
            throw new BootstrapGitProxyException("SSH host-key configuration");
        }
    }

    private static KeyPair parseKeyPair(char[] credential) {
        try (PEMParser parser = new PEMParser(new CharArrayReader(credential))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair);
            }
            if (parsed instanceof PrivateKeyInfo privateKeyInfo) {
                PrivateKey privateKey = converter.getPrivateKey(privateKeyInfo);
                if (privateKey instanceof RSAPrivateCrtKey rsa) {
                    RSAPublicKeySpec publicSpec = new RSAPublicKeySpec(
                            rsa.getModulus(),
                            rsa.getPublicExponent());
                    return new KeyPair(
                            KeyFactory.getInstance("RSA").generatePublic(publicSpec),
                            privateKey);
                }
            }
            throw new BootstrapGitProxyException("SSH private-key validation");
        } catch (BootstrapGitProxyException error) {
            throw error;
        } catch (Exception error) {
            throw new BootstrapGitProxyException("SSH private-key validation");
        }
    }

    private static void close(GitClientTransport transport) {
        if (transport instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception error) {
                throw new BootstrapGitProxyException("transport cleanup");
            }
        }
    }

    @FunctionalInterface
    interface TransportOperation<T> {
        T run(GitClientTransport transport) throws Exception;
    }
}
