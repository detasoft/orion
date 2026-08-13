package pro.deta.orion.git.workflow;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

public record GitRemoteRepository(Path directory, String uri) {
    private static final Set<RefUpdate.Result> ACCEPTED_HEAD_LINK_RESULTS = EnumSet.of(
            RefUpdate.Result.NEW,
            RefUpdate.Result.FORCED,
            RefUpdate.Result.NO_CHANGE);

    public static GitRemoteRepository createBare(Path directory) throws IOException {
        Repository repository = FileRepositoryBuilder.create(directory.toFile());
        try {
            repository.create(true);
            linkHead(repository);
            return new GitRemoteRepository(directory, directory.toUri().toString());
        } finally {
            repository.close();
        }
    }

    private static void linkHead(Repository repository) throws IOException {
        RefUpdate head = repository.updateRef(Constants.HEAD, true);
        RefUpdate.Result result = head.link(Constants.R_HEADS + "main");
        if (!ACCEPTED_HEAD_LINK_RESULTS.contains(result)) {
            throw new IOException("Failed to link remote HEAD to main: " + result);
        }
    }
}
