package pro.deta.orion.git.proxy;

import java.util.Locale;

enum BootstrapGitCredentialKind {
    NONE,
    HTTP_BEARER,
    HTTP_BASIC,
    SSH_PASSWORD,
    SSH_PRIVATE_KEY;

    static BootstrapGitCredentialKind parse(String value, boolean fileTransport) {
        if (value == null || value.isBlank()) {
            if (fileTransport) {
                return NONE;
            }
            throw new IllegalArgumentException("Remote Git credential kind must be configured");
        }
        try {
            return valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported remote Git credential kind");
        }
    }
}
