package pro.deta.orion.schema.acl;

import java.util.ArrayList;
import java.util.List;

public record AccessControl(List<User> users, List<Role> roles, List<Grant> grants) {
    public static final String TRUE_STRING = "true";

    public AccessControl {
        users = immutableElements(users);
        roles = immutableElements(roles);
        grants = immutableElements(grants);
    }

    public AccessControl() {
        this(List.of(), List.of(), List.of());
    }

    static <T> List<T> immutableElements(List<T> source) {
        if (source == null) {
            return List.of();
        }
        List<T> result = new ArrayList<>(source.size());
        for (T element : source) {
            if (element != null) {
                result.add(element);
            }
        }
        return List.copyOf(result);
    }
}
