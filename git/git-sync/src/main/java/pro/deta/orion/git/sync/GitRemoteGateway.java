package pro.deta.orion.git.sync;

import pro.deta.orion.git.nativestorage.NativeGitRepository;

import java.util.Map;

public interface GitRemoteGateway extends AutoCloseable {
    GitFetchedHeads fetchHeads(NativeGitRepository repository)
            throws GitRemoteException;

    Map<String, String> listHeads() throws GitRemoteException;

    GitPushOutcome pushHead(
            NativeGitRepository repository,
            String refName,
            String expectedRemoteId,
            String desiredId) throws GitRemoteException;

    @Override
    void close();
}
