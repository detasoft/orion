package pro.deta.orion.keymaterial;

public enum KeyMaterialPurpose {
    SERVER_SIGNING("server-signing"),
    TLS_IDENTITY("tls-identity"),
    SSH_HOST("ssh-host"),
    CERTIFICATE_AUTHORITY("certificate-authority"),
    CONFIGURATION_CIPHER("configuration-cipher");

    private final String storageName;

    KeyMaterialPurpose(String storageName) {
        this.storageName = storageName;
    }

    public String storageName() {
        return storageName;
    }
}
