package pro.deta.orion.util;

@FunctionalInterface
public interface CheckedFunction<T, R, E extends Exception> {
    R apply(T value) throws E;
}
