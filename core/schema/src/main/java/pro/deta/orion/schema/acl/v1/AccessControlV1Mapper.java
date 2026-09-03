package pro.deta.orion.schema.acl.v1;

import pro.deta.orion.schema.acl.AccessControl;

import java.util.ArrayList;
import java.util.List;

public final class AccessControlV1Mapper {
    private AccessControlV1Mapper() {
    }

    public static AccessControl toCurrent(AccessControlV1 source) {
        if (source == null) {
            return new AccessControl();
        }

        List<AccessControl.User> users = new ArrayList<>();
        for (AccessControlV1.User user : listOrEmpty(source.getUsers())) {
            if (user != null) {
                users.add(toCurrent(user));
            }
        }

        List<AccessControl.Role> roles = new ArrayList<>();
        for (AccessControlV1.Role role : listOrEmpty(source.getRoles())) {
            if (role != null) {
                roles.add(toCurrent(role));
            }
        }

        return new AccessControl(users, roles, toCurrentGrants(source.getGrants()));
    }

    private static AccessControl.User toCurrent(AccessControlV1.User source) {
        List<AccessControl.Credential> credentials = new ArrayList<>();
        for (AccessControlV1.Credential credential : listOrEmpty(source.getCredentials())) {
            if (credential != null) {
                credentials.add(toCurrent(credential));
            }
        }

        return new AccessControl.User(
                source.getId(),
                source.getFirst(),
                source.getLast(),
                source.getEmail(),
                credentials,
                listOrEmpty(source.getRoles()),
                toCurrentGrants(source.getGrants()));
    }

    private static AccessControl.Credential toCurrent(AccessControlV1.Credential source) {
        return new AccessControl.Credential(
                toCurrent(source.getType()),
                source.getKeyId(),
                source.getValue());
    }

    private static AccessControl.Role toCurrent(AccessControlV1.Role source) {
        return new AccessControl.Role(
                source.getId(),
                toCurrentGrants(source.getGrants()),
                listOrEmpty(source.getGrantReferences()));
    }

    private static List<AccessControl.Grant> toCurrentGrants(List<AccessControlV1.Grant> source) {
        List<AccessControl.Grant> result = new ArrayList<>();
        for (AccessControlV1.Grant grant : listOrEmpty(source)) {
            if (grant != null) {
                result.add(toCurrent(grant));
            }
        }
        return result;
    }

    private static AccessControl.Grant toCurrent(AccessControlV1.Grant source) {
        List<AccessControl.GrantExpression> info = new ArrayList<>();
        for (AccessControlV1.GrantExpression expression : listOrEmpty(source.getInfo())) {
            if (expression != null) {
                info.add(toCurrent(expression));
            }
        }
        return new AccessControl.Grant(source.getId(), info);
    }

    private static AccessControl.GrantExpression toCurrent(AccessControlV1.GrantExpression source) {
        return new AccessControl.GrantExpression(toCurrent(source.getKey()), source.getValue());
    }

    private static AccessControl.CredentialType toCurrent(AccessControlV1.CredentialType source) {
        if (source == null) {
            return null;
        }
        return AccessControl.CredentialType.valueOf(source.name());
    }

    private static AccessControl.GrantKey toCurrent(AccessControlV1.GrantKey source) {
        if (source == null) {
            return null;
        }
        return AccessControl.GrantKey.valueOf(source.name());
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        if (list == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(list);
    }
}
