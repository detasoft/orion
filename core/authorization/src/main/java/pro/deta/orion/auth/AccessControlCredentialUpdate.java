package pro.deta.orion.auth;

import pro.deta.orion.schema.acl.CredentialType;

public record AccessControlCredentialUpdate(CredentialType type, String keyId, String value) {
    public AccessControlCredentialUpdate(CredentialType type, String value) {
        this(type, null, value);
    }
}
