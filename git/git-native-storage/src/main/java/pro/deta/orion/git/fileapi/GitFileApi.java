package pro.deta.orion.git.fileapi;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.util.VcResource;
import pro.deta.orion.util.VcRevision;

public final class GitFileApi extends VcResource<GitFileAccess> {
    final NativeGitRepository repository;

    public GitFileApi(NativeGitRepository repository) {
        super(repository.events());
        this.repository = repository;
    }

    @Override
    protected GitFileAccess createOperation(VcRevision.ReadTarget revision) {
        return GitFileApiSupport.open(this, revision);
    }
}
