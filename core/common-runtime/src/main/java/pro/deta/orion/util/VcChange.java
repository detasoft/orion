package pro.deta.orion.util;


@FunctionalInterface
public interface VcChange<M> {
    void apply(M changes);
}
