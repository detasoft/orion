package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GitObjectGraph {
    private final GitStorageApi storage;

    public GitObjectGraph(GitStorageApi storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public Set<ObjectId> reachableObjects(Set<ObjectId> roots, boolean ignoreMissing) throws IOException {
        return traverse(roots, ignoreMissing)
                .orElseThrow(() -> new FileNotFoundException("Incomplete Git object graph"));
    }

    private Optional<Set<ObjectId>> traverse(Set<ObjectId> roots, boolean ignoreMissing) throws IOException {
        Set<ObjectId> visited = new LinkedHashSet<>();
        Set<ObjectId> found = new LinkedHashSet<>();
        ArrayDeque<ObjectId> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            ObjectId id = pending.removeFirst();
            if (!visited.add(id)) {
                continue;
            }
            Optional<GitObjectLinks> object = links(id);
            if (object.isEmpty()) {
                if (ignoreMissing) {
                    continue;
                }
                return Optional.empty();
            }
            found.add(id);
            pending.addAll(object.orElseThrow().targets());
        }
        return Optional.of(found);
    }

    public boolean hasCompleteClosure(ObjectId root) throws IOException {
        return traverse(Set.of(root), false).isPresent();
    }

    public boolean isAncestor(ObjectId ancestor, ObjectId descendant) throws IOException {
        Optional<GitObjectLinks> ancestorObject = links(ancestor);
        if (ancestorObject.isEmpty() || ancestorObject.orElseThrow().type() != GitObjectType.COMMIT) {
            return false;
        }
        Set<ObjectId> visited = new LinkedHashSet<>();
        ArrayDeque<ObjectId> pending = new ArrayDeque<>();
        pending.add(descendant);
        while (!pending.isEmpty()) {
            ObjectId id = pending.removeFirst();
            if (!visited.add(id)) {
                continue;
            }
            Optional<GitObjectLinks> object = links(id);
            if (object.isEmpty() || object.orElseThrow().type() != GitObjectType.COMMIT) {
                continue;
            }
            if (id.equals(ancestor)) {
                return true;
            }
            GitObjectLinks links = object.orElseThrow();
            pending.addAll(links.targets().subList(1, links.targets().size()));
        }
        return false;
    }

    public Optional<ObjectId> peel(ObjectId id) throws IOException {
        Set<ObjectId> visited = new HashSet<>();
        ObjectId current = id;
        ResolvedGitObjectRead<GitObjectLinks> reader = new ResolvedGitObjectRead<>(storage,
                (type, size, base, input) -> tagTarget(type, size, input));
        while (visited.size() <= 256 && visited.add(current)) {
            ObjectId object = current;
            GitObjectLinks links = storage.readObject(object, (type, size, base, input) -> switch (type) {
                case TAG, REF_DELTA, OFS_DELTA -> reader.read(type, size, base, input);
                default -> new GitObjectLinks(type, List.of());
            }).orElse(null);
            if (links == null) {
                return Optional.empty();
            }
            if (links.type() != GitObjectType.TAG) {
                return current.equals(id) ? Optional.empty() : Optional.of(current);
            }
            if (links.targets().isEmpty()) {
                return Optional.empty();
            }
            current = links.targets().getFirst();
        }
        return Optional.empty();
    }

    private static GitObjectLinks tagTarget(GitObjectType type, long size, BufferedByteInputV2 input)
            throws IOException {
        if (type != GitObjectType.TAG || size < 48) {
            return new GitObjectLinks(type, List.of());
        }
        String line = new String(input.readBytes(48), StandardCharsets.US_ASCII);
        if (!line.startsWith("object ") || line.charAt(47) != '\n') {
            return new GitObjectLinks(type, List.of());
        }
        try {
            return new GitObjectLinks(type, List.of(new ObjectId(line.substring(7, 47))));
        } catch (IllegalArgumentException malformed) {
            return new GitObjectLinks(type, List.of());
        }
    }

    private Optional<GitObjectLinks> links(ObjectId id) throws IOException {
        return storage.readObject(id, new ResolvedGitObjectRead<>(storage, GitObjectLinks::read));
    }
}
