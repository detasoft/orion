package pro.deta.orion.internal;

@FunctionalInterface
public interface CheckedFunction<T, R> {

    R apply(T t) throws Exception;

}
