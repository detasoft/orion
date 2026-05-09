package pro.deta.orion.auth;

import pro.deta.orion.acl.schema.AccessControl;

public record AccessControlCredentialUpdate(AccessControl.CredentialType type, String keyId, String value) {
    public AccessControlCredentialUpdate(AccessControl.CredentialType type, String value) {
        this(type, null, value);
    }
}
