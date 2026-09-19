package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.read.GitObjectContent;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public final class GitPackObjectResolver implements AutoCloseable {
    private final PackUpload upload;

    public GitPackObjectResolver(PackUpload upload) {
        this.upload = Objects.requireNonNull(upload, "upload");
    }

    public void attemptResolve(PackObjectParser.Result<Optional<ObjectId>> result) throws IOException {
        throw new UnsupportedOperationException("Pack object resolution is not implemented");
    }

    public Optional<GitObjectContent> getObject(ObjectId objectId) throws IOException {
        throw new UnsupportedOperationException("Resolved object lookup is not implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Pack object resolver cleanup is not implemented");
    }
}
