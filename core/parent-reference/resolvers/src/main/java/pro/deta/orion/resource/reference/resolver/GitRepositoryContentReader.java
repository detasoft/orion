package pro.deta.orion.resource.reference.resolver;

import java.util.Optional;

public interface GitRepositoryContentReader {
    Optional<byte[]> read(GitRepositoryLocation repository, String path, String ref);
}
