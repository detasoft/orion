package pro.deta.orion.acl.schema;

public abstract class CloneToUnmodifiable<T extends CloneToUnmodifiable<?>> {
    public abstract T unmodify();
    public abstract T modify();
}