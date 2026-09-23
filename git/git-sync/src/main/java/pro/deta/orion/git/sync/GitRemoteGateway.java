package pro.deta.orion.git.sync;

import pro.deta.orion.git.nativestorage.NativeGitRepository;

public interface GitRemoteGateway extends AutoCloseable {
    GitHeads fetchHeads(NativeGitRepository repository)
            throws GitRemoteException;

    GitHeads listHeads() throws GitRemoteException;

    GitPushOutcome pushHead(
            NativeGitRepository repository,
            String refName,
            String expectedRemoteId,
            String desiredId) throws GitRemoteException;

    @Override
    void close();
}
