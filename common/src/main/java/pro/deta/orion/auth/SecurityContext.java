package pro.deta.orion.auth;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@ToString
public class SecurityContext {
    private final Map<String, Object> attributes = new HashMap<>();

    public static SecurityContext createContext() {
        return new SecurityContext();
    }

    private SecurityContext() {}

    public <A> SecurityContext with(Permission<A> attribute, A o) {
        return with(attribute.getName(), o);
    }

    public <A> SecurityContext with(String name, A o) {
        if (attributes.containsKey(name))
            log.warn("Can't update attribute {} value to [{}]", name, o);
        else
            attributes.put(name, o);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <A> A getAttribute(Permission<A> attribute) {
        return (A) attributes.get(attribute.getName());
    }

    public String formatShort() {
        return getAttribute(Permission.USER_IDENTITY).toString();
    }
}
