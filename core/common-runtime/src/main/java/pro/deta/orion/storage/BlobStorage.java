package pro.deta.orion.storage;

import java.io.IOException;
import java.util.function.Consumer;

import pro.deta.orion.util.Subscription;
import pro.deta.orion.util.VcResource;

public abstract class BlobStorage implements VcResource {
    public abstract byte[] read(String revision) throws IOException;

    protected abstract String write(byte[] content) throws IOException;

    public abstract Subscription subscribe(Consumer<BlobChanged> subscriber);
}
