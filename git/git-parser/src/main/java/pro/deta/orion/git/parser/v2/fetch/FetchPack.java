package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.read.GitObjectLinks;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FetchPack {
    private final GitStorageApi storage;
    private final Set<ObjectId> objects = new LinkedHashSet<>();
    private final Set<ObjectId> common = new HashSet<>();
    private final boolean thin;

    private FetchPack(GitStorageApi storage, boolean thin) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.thin = thin;
    }

    public static FetchPack prepare(GitStorageApi storage, FetchPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        if (plan.depth().isPresent() || plan.deepenSince().isPresent() || !plan.deepenNot().isEmpty()
                || plan.capabilities().has(GitCapability.DEEPEN_RELATIVE)) {
            throw new IOException("Deepening fetch history is not implemented");
        }
        if (plan.filter().isPresent()) {
            throw new IOException("Filtered fetch is not implemented");
        }
        FetchPack pack = new FetchPack(storage, plan.capabilities().has(GitCapability.THIN_PACK));
        ArrayDeque<ObjectId> pending = new ArrayDeque<>(plan.commonObjects());
        while (!pending.isEmpty()) {
            ObjectId id = pending.removeFirst();
            if (pack.common.add(id)) {
                GitObjectLinks links = pack.readLinks(id);
                enqueue(pending, id, links, plan.shallowCommits());
            }
        }
        pending.addAll(plan.wantedObjects());
        while (!pending.isEmpty()) {
            ObjectId id = pending.removeFirst();
            if (!pack.common.contains(id) && pack.objects.add(id)) {
                GitObjectLinks links = pack.readLinks(id);
                enqueue(pending, id, links, plan.shallowCommits());
            }
        }
        if (plan.capabilities().has(GitCapability.INCLUDE_TAG)) {
            pack.includeTags();
        }
        return pack;
    }

    public long objectCount() {
        return objects.size();
    }

    public void writeTo(PackWriter writer) throws IOException {
        for (ObjectId id : objects) {
            storage.readObject(id, (type, size, base, input) -> {
                if (base.isEmpty() || objects.contains(base.orElseThrow())
                        || thin && common.contains(base.orElseThrow())) {
                    return writer.writeCompressed(type, size, base, input);
                }
                ResolvedGitObjectRead<Long> reader = new ResolvedGitObjectRead<>(storage,
                        (resolvedType, length, unused, content) -> writer.writeObject(resolvedType, length, content));
                return reader.read(type, size, base, input);
            }).orElseThrow(() -> missing(id));
        }
    }

    private GitObjectLinks readLinks(ObjectId id) throws IOException {
        return storage.readObject(id, (type, size, base, input) -> {
            if (type == GitObjectType.BLOB) {
                return new GitObjectLinks(type, List.of());
            }
            ResolvedGitObjectRead<GitObjectLinks> reader =
                    new ResolvedGitObjectRead<>(storage, GitObjectLinks::read);
            return reader.read(type, size, base, input);
        }).orElseThrow(() -> missing(id));
    }

    private static void enqueue(ArrayDeque<ObjectId> pending, ObjectId id, GitObjectLinks links,
                                Set<ObjectId> shallow) {
        if (links.type() == GitObjectType.COMMIT && shallow.contains(id)) {
            pending.addLast(links.targets().getFirst());
        } else {
            pending.addAll(links.targets());
        }
    }

    private void includeTags() throws IOException {
        Map<ObjectId, GitObjectLinks> tags = new LinkedHashMap<>();
        for (Map.Entry<RefId, ObjectId> ref : storage.snapshotRefs().refs().entrySet()) {
            if (ref.getKey().value().startsWith("refs/tags/")) {
                ObjectId id = ref.getValue();
                Set<ObjectId> visited = new HashSet<>();
                while (!objects.contains(id) && !common.contains(id) && visited.add(id)) {
                    GitObjectLinks links = readLinks(id);
                    if (links.type() != GitObjectType.TAG) {
                        break;
                    }
                    tags.put(id, links);
                    id = links.targets().getFirst();
                }
            }
        }
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<ObjectId, GitObjectLinks> tag : tags.entrySet()) {
                if (!objects.contains(tag.getKey())
                        && objects.contains(tag.getValue().targets().getFirst())) {
                    objects.add(tag.getKey());
                    changed = true;
                }
            }
        } while (changed);
    }

    private static IOException missing(ObjectId id) {
        return new IOException("Missing fetch object: " + id.toHex());
    }
}
