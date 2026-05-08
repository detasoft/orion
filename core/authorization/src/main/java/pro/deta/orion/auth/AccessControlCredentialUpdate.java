package pro.deta.orion.auth;

import pro.deta.orion.acl.schema.AccessControl;

public record AccessControlCredentialUpdate(AccessControl.CredentialType type, String value) {
}
