package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.util.VcResource;
import pro.deta.orion.util.VcRevision;

import java.io.IOException;
import java.util.Objects;

/** A versioned view of one branch; each modification publishes one commit. */
public final class GitFileApi extends VcResource<GitFileApi.GitFileReadOperations, GitFileAccess> {
    private final NativeGitRepository repository;
    private final boolean initializeHead;
    private final String branch;

    public GitFileApi(NativeGitRepository repository, String branch) {
        this(repository, branch, true);
    }

    public GitFileApi(NativeGitRepository repository, String branch, boolean initializeHead) {
        super(repository.events(), GitFileApiSupport.initialRevision(repository, branch));
        this.repository = Objects.requireNonNull(repository, "repository");
        this.branch = GitFileApiSupport.branchRefName(branch);
        this.initializeHead = initializeHead;
    }

    @Override
    protected GitFileReadOperations createReadOperation(VcRevision.ReadTarget revision) {
        return new GitFileReadOperations(revision);
    }

    public final class GitFileReadOperations {
        private final VcRevision.ReadTarget revision;

        private GitFileReadOperations(VcRevision.ReadTarget revision) {
            this.revision = revision;
        }

        private ObjectId commitId() throws GitOperationException {
            return revision instanceof VcRevision.Stored stored
                    ? new ObjectId(stored.value()) : GitFileApiSupport.resolveBranch(repository, branch);
        }

        public <T> T readFile(String path, GitObjectRead<T> reader) throws IOException, GitOperationException {
            return GitFileApiSupport.readFile(repository, commitId(), path, reader);
        }

        public byte[] readBytes(String path) throws IOException, GitOperationException {
            return readFile(path, (type, size, base, input) -> input.newInputStream().readAllBytes());
        }

        public ObjectId objectId(String path) throws GitOperationException {
            return GitFileApiSupport.fileEntry(repository, commitId(), path).objectId();
        }
    }

    @Override
    protected GitFileAccess createUpdateOperation() {
        return GitFileApiSupport.createAccess(repository, branch, initializeHead);
    }
}
