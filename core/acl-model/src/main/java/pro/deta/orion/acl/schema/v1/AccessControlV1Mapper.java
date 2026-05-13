package pro.deta.orion.acl.schema.v1;

import pro.deta.orion.acl.schema.AccessControl;

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

    public static AccessControlV1 fromCurrent(AccessControl source) {
        if (source == null) {
            return new AccessControlV1(
                    AccessControlV1.SchemaVersion.V1,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>());
        }

        List<AccessControlV1.User> users = new ArrayList<>();
        for (AccessControl.User user : listOrEmpty(source.getUsers())) {
            if (user != null) {
                users.add(fromCurrent(user));
            }
        }

        List<AccessControlV1.Role> roles = new ArrayList<>();
        for (AccessControl.Role role : listOrEmpty(source.getRoles())) {
            if (role != null) {
                roles.add(fromCurrent(role));
            }
        }

        return new AccessControlV1(
                AccessControlV1.SchemaVersion.V1,
                users,
                roles,
                fromCurrentGrants(source.getGrants()));
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

    private static AccessControlV1.User fromCurrent(AccessControl.User source) {
        List<AccessControlV1.Credential> credentials = new ArrayList<>();
        for (AccessControl.Credential credential : listOrEmpty(source.getCredentials())) {
            if (credential != null) {
                credentials.add(fromCurrent(credential));
            }
        }

        return new AccessControlV1.User(
                source.getId(),
                source.getFirst(),
                source.getLast(),
                source.getEmail(),
                credentials,
                listOrEmpty(source.getRoles()),
                fromCurrentGrants(source.getGrants()));
    }

    private static AccessControl.Credential toCurrent(AccessControlV1.Credential source) {
        return new AccessControl.Credential(
                toCurrent(source.getType()),
                source.getKeyId(),
                source.getValue());
    }

    private static AccessControlV1.Credential fromCurrent(AccessControl.Credential source) {
        return new AccessControlV1.Credential(
                fromCurrent(source.getType()),
                source.getKeyId(),
                source.getValue());
    }

    private static AccessControl.Role toCurrent(AccessControlV1.Role source) {
        return new AccessControl.Role(
                source.getId(),
                toCurrentGrants(source.getGrants()),
                listOrEmpty(source.getGrantReferences()));
    }

    private static AccessControlV1.Role fromCurrent(AccessControl.Role source) {
        return new AccessControlV1.Role(
                source.getId(),
                fromCurrentGrants(source.getGrants()),
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

    private static List<AccessControlV1.Grant> fromCurrentGrants(List<AccessControl.Grant> source) {
        List<AccessControlV1.Grant> result = new ArrayList<>();
        for (AccessControl.Grant grant : listOrEmpty(source)) {
            if (grant != null) {
                result.add(fromCurrent(grant));
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

    private static AccessControlV1.Grant fromCurrent(AccessControl.Grant source) {
        List<AccessControlV1.GrantExpression> info = new ArrayList<>();
        for (AccessControl.GrantExpression expression : listOrEmpty(source.getInfo())) {
            if (expression != null) {
                info.add(fromCurrent(expression));
            }
        }
        return new AccessControlV1.Grant(source.getId(), info);
    }

    private static AccessControl.GrantExpression toCurrent(AccessControlV1.GrantExpression source) {
        return new AccessControl.GrantExpression(toCurrent(source.getKey()), source.getValue());
    }

    private static AccessControlV1.GrantExpression fromCurrent(AccessControl.GrantExpression source) {
        return new AccessControlV1.GrantExpression(fromCurrent(source.getKey()), source.getValue());
    }

    private static AccessControl.CredentialType toCurrent(AccessControlV1.CredentialType source) {
        if (source == null) {
            return null;
        }
        return AccessControl.CredentialType.valueOf(source.name());
    }

    private static AccessControlV1.CredentialType fromCurrent(AccessControl.CredentialType source) {
        if (source == null) {
            return null;
        }
        return AccessControlV1.CredentialType.valueOf(source.name());
    }

    private static AccessControl.GrantKey toCurrent(AccessControlV1.GrantKey source) {
        if (source == null) {
            return null;
        }
        return AccessControl.GrantKey.valueOf(source.name());
    }

    private static AccessControlV1.GrantKey fromCurrent(AccessControl.GrantKey source) {
        if (source == null) {
            return null;
        }
        return AccessControlV1.GrantKey.valueOf(source.name());
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        if (list == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(list);
    }
}
