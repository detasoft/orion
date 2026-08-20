package pro.deta.orion.auth;

import pro.deta.orion.schema.acl.AccessControl;

public record AccessControlCredentialUpdate(AccessControl.CredentialType type, String keyId, String value) {
    public AccessControlCredentialUpdate(AccessControl.CredentialType type, String value) {
        this(type, null, value);
    }
}
