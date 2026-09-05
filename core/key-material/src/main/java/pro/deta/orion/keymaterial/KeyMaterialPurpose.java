package pro.deta.orion.keymaterial;

public enum KeyMaterialPurpose {
    SERVER_SIGNING("server-signing"),
    ACME_ACCOUNT("acme-account"),
    TLS_IDENTITY("tls-identity"),
    SSH_HOST("ssh-host"),
    SSH_CLIENT("ssh-client"),
    CERTIFICATE_AUTHORITY("certificate-authority"),
    TRUST_ANCHOR("trust-anchor"),
    CONFIGURATION_CIPHER("configuration-cipher");

    private final String storageName;

    KeyMaterialPurpose(String storageName) {
        this.storageName = storageName;
    }

    public String storageName() {
        return storageName;
    }
}
