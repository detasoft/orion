package pro.deta.orion.internal.system;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Slf4j
public class JavaLangAccessInterop implements OrionJavaLangAccess {
    private final Object javaLangAccess;
    private final Method setCauseMethod;

    public JavaLangAccessInterop() throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException, NoSuchMethodException {
        Class<?> memberClass = Class.forName("jdk.internal.access.SharedSecrets");
        Class<?> jlaClass = Class.forName("jdk.internal.access.JavaLangAccess");
        Field jlaField = memberClass.getDeclaredField("javaLangAccess");
        jlaField.setAccessible(true);
        javaLangAccess = jlaField.get(null);
        setCauseMethod = jlaClass.getMethod("setCause", Throwable.class, Throwable.class);
    }

    @Override
    public void setCause(Throwable t, Throwable cause) {
        try {
            setCauseMethod.invoke(javaLangAccess, t, cause);
        } catch (Throwable e) {
            log.trace("Call to setCause(t,t) failed: {}", e.getMessage());
        }
    }
}
