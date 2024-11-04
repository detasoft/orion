package pro.deta.orion.internal;

@FunctionalInterface
public interface CheckedConsumer<T> {
        void accept(T t) throws Exception;
}
