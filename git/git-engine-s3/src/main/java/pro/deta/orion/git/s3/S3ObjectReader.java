package pro.deta.orion.git.s3;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.*;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class S3ObjectReader extends ObjectReader {
    @Override
    public ObjectReader newReader() {
        return null;
    }

    @Override
    public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException {
        return List.of();
    }

    @Override
    public ObjectLoader open(AnyObjectId objectId, int typeHint) throws MissingObjectException, IncorrectObjectTypeException, IOException {
        return null;
    }

    @Override
    public Set<ObjectId> getShallowCommits() throws IOException {
        return Set.of();
    }

    @Override
    public void close() {

    }
}
