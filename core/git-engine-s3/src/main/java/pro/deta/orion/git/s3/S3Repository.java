package pro.deta.orion.git.s3;

import lombok.Getter;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.*;

import java.io.IOException;

public class S3Repository extends Repository {
    private final AbstractClient client;

    @Getter
    private RefDatabase refDatabase;
    @Getter
    private final ObjectDatabase objectDatabase;

    /**
     * Initialize a new repository instance.
     *
     */
    public S3Repository(AbstractClient client) {
        super(new RepositoryBuilder());
        this.client = client;
        refDatabase = new S3RefDatabase(client);
        objectDatabase = new S3ObjectDatabase(client);
    }

    @Override
    public void create(boolean bare) throws IOException {
        client.createBucket();
        refDatabase.create();
        objectDatabase.create();
    }

    @Override
    public String getIdentifier() {
        return client.getBucketName() + ":" + client.getPath();
    }

    @Override
    public StoredConfig getConfig() {
        return new StoredConfig() {
            @Override
            public void load() throws IOException, ConfigInvalidException {

            }

            @Override
            public void save() throws IOException {

            }
        };
//        throw new UnsupportedOperationException("Unsupported operation");
    }

    @Override
    public AttributesNodeProvider createAttributesNodeProvider() {
        throw new UnsupportedOperationException("Unsupported operation");
    }

    @Override
    public void scanForRepoChanges() throws IOException {

    }

    @Override
    public void notifyIndexChanged(boolean internal) {

    }

    @Override
    public ReflogReader getReflogReader(String refName) throws IOException {
        throw new UnsupportedOperationException("Unsupported operation");
    }
}
