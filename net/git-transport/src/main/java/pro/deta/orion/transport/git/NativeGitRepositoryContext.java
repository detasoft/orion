package pro.deta.orion.transport.git;

import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.NativeGitReceivePack;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.fetch.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.read.GitObjectGraph;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class NativeGitRepositoryContext extends GitRepositoryContext {
    private final String name;
    private final Optional<String> packUriBase;
    private final NativeGitRepository repository;
    private final NativeGitRepositoryProvider provider;
    private final GitNativeRepositoryAccessHook accessHook;

    NativeGitRepositoryContext(String name, NativeGitRepository repository,
            NativeGitRepositoryProvider provider, GitNativeRepositoryAccessHook accessHook,
            Optional<String> packUriBase) {
        super(repository.storage());
        this.name = name;
        this.packUriBase = packUriBase;
        this.repository = repository;
        this.provider = provider;
        this.accessHook = accessHook;
    }

    @Override
    public Optional<URI> packUri(PackId id) {
        return packUriBase.map(base -> {
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return URI.create(base + "/" + name + ".git/objects/pack/" + id.toHex() + ".pack");
        });
    }

    @Override
    public void checkFetchAccess(FetchRequest request) throws IOException {
        RefsSnapshot snapshot = storage().snapshotRefs();
        Set<ObjectId> wants = new LinkedHashSet<>(request.wants());
        for (String ref : request.wantRefs()) {
            ObjectId id = ref.equals("HEAD") ? switch (snapshot.head()) {
                case Head.Symbolic head -> snapshot.refs().get(head.target());
                case Head.Detached head -> new ObjectId(head.target().toHex());
            } : snapshot.refs().get(new RefId(ref));
            if (id == null) {
                accessHook.beforeFetch(name, List.of());
            } else {
                wants.add(id);
            }
        }
        if (request.mode() != FetchRequest.Mode.PROTOCOL_V2) {
            for (ObjectId want : wants) {
                if (!snapshot.refs().containsValue(want)
                        && !(snapshot.head() instanceof Head.Detached head
                        && head.target().toHex().equals(want.toHex()))) {
                    throw new IOException("Want is not an advertised object: " + want.toHex());
                }
            }
        }
        GitObjectGraph graph = new GitObjectGraph(storage());
        Map<ObjectId, List<String>> branches = new LinkedHashMap<>();
        for (ObjectId want : wants) {
            branches.put(graph.peel(want).orElse(want), new ArrayList<>());
        }
        List<RefId> refs = new ArrayList<>(snapshot.refs().keySet());
        refs.sort((left, right) -> left.value().compareTo(right.value()));
        for (RefId ref : refs) {
            if (!ref.value().startsWith("refs/heads/")) {
                continue;
            }
            Set<ObjectId> reachable = graph.reachableObjects(
                    Set.of(snapshot.refs().get(ref)), true);
            for (Map.Entry<ObjectId, List<String>> entry : branches.entrySet()) {
                if (reachable.contains(entry.getKey())) {
                    entry.getValue().add(ref.value().substring("refs/heads/".length()));
                }
            }
        }
        for (List<String> names : branches.values()) {
            accessHook.beforeFetch(name, List.copyOf(names));
        }
    }

    @Override
    public List<RefUpdateResult> publish(Optional<IndexedPack> pack, List<RefUpdate> updates, boolean atomic)
            throws IOException {
        Optional<PackId> received = pack.isPresent()
                ? Optional.of(storage().persist(pack.orElseThrow())) : Optional.empty();
        return NativeGitReceivePack.complete(name, repository, updates, atomic, accessHook,
                accepted -> provider.publish(repository, received, accepted, atomic));
    }
}
