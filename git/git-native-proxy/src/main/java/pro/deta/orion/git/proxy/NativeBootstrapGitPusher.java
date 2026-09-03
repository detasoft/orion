package pro.deta.orion.git.proxy;

import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.upload.NativeFetchOptions;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NativeBootstrapGitPusher implements BootstrapGitPusher {
    private static final String NULL_ID = "0".repeat(40);
    private static final GitClientOptions OPTIONS = GitClientOptions.defaults();

    @Override
    public List<Boolean> push(
            BootstrapGitLocation location,
            GitClientTransport transport,
            NativeGitRepository repository,
            List<LooseRefStore.Update> updates,
            boolean atomic) {
        List<GitReceivePackRequest.Command> commands = new ArrayList<>(updates.size());
        for (LooseRefStore.Update update : updates) {
            commands.add(new GitReceivePackRequest.Command(
                    update.expectedOldId(),
                    update.newId(),
                    update.refName()));
        }
        GitReceivePackRequest request = new GitReceivePackRequest(
                commands,
                output -> writePack(repository, updates, output),
                requestAtomic(updates.size(), atomic));
        GitClientResult<GitReceivePackResult> result = new GitReceivePackClient(transport).push(
                location.remoteUri(),
                OPTIONS,
                request);
        if (!(result instanceof GitClientResult.Success<GitReceivePackResult> success)) {
            throw new BootstrapGitProxyException("upstream ref publication");
        }
        return accepted(updates, success.value());
    }

    static boolean requestAtomic(int updateCount, boolean atomic) {
        return atomic && updateCount > 1;
    }

    static List<Boolean> accepted(
            List<LooseRefStore.Update> updates,
            GitReceivePackResult result) {
        Map<String, GitReceivePackResult.RefStatus> statuses = new HashMap<>();
        for (GitReceivePackResult.RefStatus status : result.refs()) {
            statuses.put(status.refName(), status);
        }
        boolean unpacked = "ok".equals(result.unpackStatus());
        List<Boolean> accepted = new ArrayList<>(updates.size());
        for (LooseRefStore.Update update : updates) {
            GitReceivePackResult.RefStatus status = statuses.get(update.refName());
            if (status == null) {
                throw new BootstrapGitProxyException("upstream ref publication");
            }
            accepted.add(unpacked && status.accepted());
        }
        return List.copyOf(accepted);
    }

    private static void writePack(
            NativeGitRepository repository,
            List<LooseRefStore.Update> updates,
            pro.deta.orion.net.io.BufferedByteOutput output) throws IOException {
        Set<GitObjectId> wants = new LinkedHashSet<>();
        Set<GitObjectId> haves = new LinkedHashSet<>();
        for (LooseRefStore.Update update : updates) {
            if (!NULL_ID.equals(update.newId())) {
                wants.add(GitObjectId.of(update.newId()));
            }
            if (!NULL_ID.equals(update.expectedOldId())) {
                haves.add(GitObjectId.of(update.expectedOldId()));
            }
        }
        if (wants.isEmpty()) {
            return;
        }
        NativeFetchRequest request = new NativeFetchRequest(
                wants,
                haves,
                true,
                Set.of(),
                NativeFetchOptions.initial(false, true, false));
        try (NativePackProducer producer = repository.fetch(request)) {
            while (producer.produce(output) == NativePackProducer.Result.MORE) {
                // Continue until the complete native pack has been written.
            }
            output.flush();
        }
    }
}
