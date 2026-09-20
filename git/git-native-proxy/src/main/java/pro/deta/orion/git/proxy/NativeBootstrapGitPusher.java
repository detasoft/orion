package pro.deta.orion.git.proxy;

import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitClientTransport;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitReceivePackRequest;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.fetch.FetchPack;
import pro.deta.orion.git.parser.v2.fetch.FetchPlan;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.read.GitObjectGraph;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

final class NativeBootstrapGitPusher implements BootstrapGitPusher {
    private static final String NULL_ID = "0".repeat(40);
    private static final GitClientOptions OPTIONS = GitClientOptions.defaults();

    @Override
    public List<Boolean> push(
            BootstrapGitLocation location,
            GitClientTransport transport,
            NativeGitRepository repository,
            Optional<PackId> received,
            List<RefUpdate> updates,
            boolean atomic) {
        List<GitReceivePackRequest.Command> commands = new ArrayList<>(updates.size());
        for (RefUpdate update : updates) {
            commands.add(new GitReceivePackRequest.Command(
                    update.expectedOld().map(ObjectId::toHex).orElse(NULL_ID),
                    update.newId().map(ObjectId::toHex).orElse(NULL_ID),
                    update.ref().value()));
        }
        GitReceivePackRequest request = new GitReceivePackRequest(
                commands,
                output -> writePack(repository, received, updates, output),
                requestAtomic(updates.size(), atomic));
        GitClientResult<GitReceivePackResult> result = new GitReceivePackClient(transport).push(
                location.remoteUri(),
                OPTIONS,
                request);
        if (!(result instanceof GitClientResult.Success<GitReceivePackResult> success)) {
            throw new BootstrapGitProxyException("upstream ref publication",
                    ((GitClientResult.Failed<GitReceivePackResult>) result).failure());
        }
        return accepted(updates, success.value());
    }

    static boolean requestAtomic(int updateCount, boolean atomic) {
        return atomic && updateCount > 1;
    }

    static List<Boolean> accepted(
            List<RefUpdate> updates,
            GitReceivePackResult result) {
        Map<String, GitReceivePackResult.RefStatus> statuses = new HashMap<>();
        for (GitReceivePackResult.RefStatus status : result.refs()) {
            statuses.put(status.refName(), status);
        }
        boolean unpacked = "ok".equals(result.unpackStatus());
        List<Boolean> accepted = new ArrayList<>(updates.size());
        for (RefUpdate update : updates) {
            GitReceivePackResult.RefStatus status = statuses.get(update.ref().value());
            if (status == null) {
                throw new BootstrapGitProxyException("upstream ref publication");
            }
            accepted.add(unpacked && status.accepted());
        }
        return List.copyOf(accepted);
    }

    private static void writePack(
            NativeGitRepository repository,
            Optional<PackId> received,
            List<RefUpdate> updates,
            pro.deta.orion.net.io.BufferedByteOutput output) throws IOException {
        Set<ObjectId> wants = new LinkedHashSet<>();
        Set<ObjectId> haves = new LinkedHashSet<>();
        for (RefUpdate update : updates) {
            if (update.newId().isPresent()) {
                wants.add(update.newId().orElseThrow());
            }
            if (update.expectedOld().isPresent()) {
                haves.add(update.expectedOld().orElseThrow());
            }
        }
        if (wants.isEmpty()) {
            return;
        }
        if (received.isPresent()) {
            GitObjectGraph graph = new GitObjectGraph(repository.storage());
            Set<ObjectId> required = graph.reachableObjects(wants, false);
            required.removeAll(graph.reachableObjects(haves, true));
            Optional<IndexedPack> stored = repository.storage().openPack(received.orElseThrow());
            if (stored.isPresent()) {
                try (IndexedPack pack = stored.orElseThrow()) {
                    if (canReusePack(pack, required)) {
                        try (BufferedByteInputV2 input = pack.input()) {
                            byte[] buffer = new byte[8192];
                            ByteBuffer source;
                            while ((source = input.buffer()) != null) {
                                int count = Math.min(source.remaining(), buffer.length);
                                source.get(buffer, 0, count);
                                output.write(buffer, 0, count);
                            }
                        }
                        output.flush();
                        return;
                    }
                }
            }
        }
        FetchPlan plan = new FetchPlan(wants, Map.of(), haves, Set.of(), OptionalInt.empty(),
                OptionalLong.empty(), Set.of(), Optional.empty(), new GitCapabilities(), Set.of());
        FetchPack pack = FetchPack.prepare(repository.storage(), plan);
        try (PackWriter writer = new PackWriter(output, pack.objectCount())) {
            pack.writeTo(writer);
            writer.finish();
        }
        output.flush();
    }

    private static boolean canReusePack(IndexedPack pack, Set<ObjectId> required) throws IOException {
        for (ObjectId id : required) {
            if (pack.find(id).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
