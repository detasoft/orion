package pro.deta.orion.schema.acl;

import pro.deta.orion.schema.acl.AccessControl.CredentialType;

public record Credential(CredentialType type, String keyId, String value) {
    public Credential(CredentialType type, String value) {
        this(type, null, value);
    }
}
