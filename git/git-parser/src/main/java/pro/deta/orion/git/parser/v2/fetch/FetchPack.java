package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.read.GitObjectLinks;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.PackObjectLocation;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class FetchPack {
    private final GitStorageApi storage;
    private final Set<ObjectId> objects = new LinkedHashSet<>();
    private final Set<ObjectId> common = new HashSet<>();
    private final Set<ObjectId> shallow = new LinkedHashSet<>();
    private final Set<ObjectId> unshallow = new LinkedHashSet<>();
    private final boolean thin;
    private final List<PackObjectLocation> entries = new ArrayList<>();

    private FetchPack(GitStorageApi storage, boolean thin) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.thin = thin;
    }

    public static FetchPack prepare(GitStorageApi storage, FetchPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        if (plan.filter().isPresent() && !plan.filter().orElseThrow().equals("blob:none")) {
            throw new IOException("Unsupported object filter: " + plan.filter().orElseThrow());
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
        pack.selectWanted(plan);
        pack.objects.removeAll(pack.common);
        pack.shallow.retainAll(pack.objects);
        if (plan.filter().isPresent()) {
            Iterator<ObjectId> iterator = pack.objects.iterator();
            while (iterator.hasNext()) {
                ObjectId id = iterator.next();
                if (!plan.wantedObjects().contains(id) && pack.readLinks(id).type() == GitObjectType.BLOB) {
                    iterator.remove();
                }
            }
        }
        if (plan.capabilities().has(GitCapability.INCLUDE_TAG)) {
            pack.includeTags();
        }
        pack.entries.addAll(storage.locateObjects(pack.objects));
        if (pack.entries.size() != pack.objects.size()) {
            throw new IOException("Missing fetch objects while preparing pack entries");
        }
        return pack;
    }

    public Set<ObjectId> shallowCommits() {
        return Collections.unmodifiableSet(shallow);
    }

    public Set<ObjectId> unshallowCommits() {
        return Collections.unmodifiableSet(unshallow);
    }

    private void selectWanted(FetchPlan plan) throws IOException {
        boolean relative = plan.capabilities().has(GitCapability.DEEPEN_RELATIVE);
        boolean deepening = plan.depth().isPresent() || plan.deepenSince().isPresent()
                || !plan.deepenNot().isEmpty();
        Set<ObjectId> excluded = excludedCommits(plan.deepenNot());
        Map<ObjectId, Integer> visited = new LinkedHashMap<>();
        Map<ObjectId, GitObjectLinks> commits = new LinkedHashMap<>();
        ArrayDeque<Map.Entry<ObjectId, Integer>> pending = new ArrayDeque<>();
        int initialDepth = relative ? Integer.MAX_VALUE : plan.depth().orElse(Integer.MAX_VALUE);
        for (ObjectId root : plan.wantedObjects()) {
            pending.addLast(Map.entry(root, initialDepth));
        }
        while (!pending.isEmpty()) {
            Map.Entry<ObjectId, Integer> current = pending.removeFirst();
            ObjectId id = current.getKey();
            int remaining = current.getValue();
            if (relative && remaining == Integer.MAX_VALUE && plan.shallowCommits().contains(id)) {
                remaining = plan.depth().orElseThrow();
                if (remaining < Integer.MAX_VALUE) {
                    remaining++;
                }
            }
            Integer previous = visited.get(id);
            if (previous != null && previous >= remaining) {
                continue;
            }
            visited.put(id, remaining);
            objects.add(id);
            GitObjectLinks links = readLinks(id);
            if (links.type() != GitObjectType.COMMIT) {
                for (ObjectId target : links.targets()) {
                    pending.addLast(Map.entry(target, remaining));
                }
                continue;
            }
            commits.put(id, links);
            pending.addLast(Map.entry(links.targets().getFirst(), Integer.MAX_VALUE));
            if (remaining == 1 || !deepening && plan.shallowCommits().contains(id)) {
                continue;
            }
            int parentDepth = remaining == Integer.MAX_VALUE ? remaining : remaining - 1;
            for (ObjectId parent : links.targets().subList(1, links.targets().size())) {
                if (!excluded.contains(parent) && (plan.deepenSince().isEmpty()
                        || commitTime(parent) > plan.deepenSince().orElseThrow())) {
                    pending.addLast(Map.entry(parent, parentDepth));
                }
            }
        }
        for (Map.Entry<ObjectId, GitObjectLinks> commit : commits.entrySet()) {
            List<ObjectId> targets = commit.getValue().targets();
            if (!commits.keySet().containsAll(targets.subList(1, targets.size()))) {
                shallow.add(commit.getKey());
            } else if (deepening && plan.shallowCommits().contains(commit.getKey())) {
                unshallow.add(commit.getKey());
            }
        }
    }

    private Set<ObjectId> excludedCommits(Set<String> refs) throws IOException {
        Set<ObjectId> result = new HashSet<>();
        if (refs.isEmpty()) {
            return result;
        }
        RefsSnapshot snapshot = storage.snapshotRefs();
        Map<RefId, ObjectId> storedRefs = snapshot.refs();
        ArrayDeque<ObjectId> pending = new ArrayDeque<>();
        for (String ref : refs) {
            ObjectId id = "HEAD".equals(ref) ? switch (snapshot.head()) {
                case Head.Symbolic head -> storedRefs.get(head.target());
                case Head.Detached head -> new ObjectId(head.target().toBytes());
            } : storedRefs.get(new RefId(ref));
            if (id == null && !"HEAD".equals(ref) && !ref.startsWith("refs/")) {
                id = storedRefs.get(new RefId("refs/heads/" + ref));
            }
            if (id == null) {
                throw new IOException("Unknown deepen-not ref: " + ref);
            }
            pending.addLast(id);
        }
        while (!pending.isEmpty()) {
            ObjectId id = pending.removeFirst();
            if (result.add(id)) {
                GitObjectLinks links = readLinks(id);
                if (links.type() == GitObjectType.COMMIT) {
                    pending.addAll(links.targets().subList(1, links.targets().size()));
                } else if (links.type() == GitObjectType.TAG) {
                    pending.addAll(links.targets());
                }
            }
        }
        return result;
    }

    private long commitTime(ObjectId id) throws IOException {
        return storage.readObject(id, new ResolvedGitObjectRead<>(storage,
                (type, size, base, input) -> readCommitTime(type, size, input)))
                .orElseThrow(() -> missing(id));
    }

    private static long readCommitTime(GitObjectType type, long remaining, BufferedByteInputV2 input)
            throws IOException {
        if (type != GitObjectType.COMMIT) {
            throw new IOException("Commit parent is not a commit");
        }
        StringBuilder prefix = new StringBuilder(10);
        StringBuilder suffix = new StringBuilder(64);
        while (remaining-- > 0) {
            int next = input.readUnsignedByte();
            if (next == '\n') {
                if (prefix.toString().equals("committer ")) {
                    String value = suffix.toString();
                    int zone = value.lastIndexOf(' ');
                    int timestamp = zone < 0 ? -1 : value.lastIndexOf(' ', zone - 1);
                    if (timestamp < 0) {
                        throw new IOException("Invalid commit timestamp");
                    }
                    try {
                        return Long.parseLong(value.substring(timestamp + 1, zone));
                    } catch (NumberFormatException failure) {
                        throw new IOException("Invalid commit timestamp", failure);
                    }
                }
                if (prefix.isEmpty()) {
                    break;
                }
                prefix.setLength(0);
                suffix.setLength(0);
            } else {
                if (prefix.length() < 10) {
                    prefix.append((char) next);
                }
                if (suffix.length() == 64) {
                    suffix.deleteCharAt(0);
                }
                suffix.append((char) next);
            }
        }
        throw new IOException("Missing commit timestamp");
    }

    public Map<PackId, URI> selectPackUris(GitRepositoryContext repository, Set<String> protocols)
            throws IOException {
        Map<PackId, URI> selected = new LinkedHashMap<>();
        if (protocols.isEmpty() || objects.isEmpty()) {
            return selected;
        }
        for (PackId id : storage.packIds()) {
            Optional<URI> uri = repository.packUri(id);
            if (uri.isEmpty() || !protocols.contains(uri.orElseThrow().getScheme())) {
                continue;
            }
            Set<ObjectId> covered = storage.packObjectIds(id);
            if (!covered.isEmpty() && objects.containsAll(covered)) {
                selected.put(id, uri.orElseThrow());
                objects.removeAll(covered);
                entries.removeIf(entry -> covered.contains(entry.objectId()));
                common.addAll(covered);
            }
            if (objects.isEmpty()) {
                break;
            }
        }
        return selected;
    }

    public long objectCount() {
        return entries.size();
    }

    public void writeTo(PackWriter writer) throws IOException {
        for (PackObjectLocation entry : entries) {
            storage.readObject(entry, (type, size, base, input) -> {
                if (base.isEmpty() || objects.contains(base.orElseThrow())
                        || thin && common.contains(base.orElseThrow())) {
                    return writer.writeCompressed(type, size, base, input);
                }
                ResolvedGitObjectRead<Long> reader = new ResolvedGitObjectRead<>(storage,
                        (resolvedType, length, unused, content) -> writer.writeObject(resolvedType, length, content));
                return reader.read(type, size, base, input);
            });
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
