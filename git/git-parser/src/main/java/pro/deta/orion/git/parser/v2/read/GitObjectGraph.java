package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
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

    private Optional<GitObjectLinks> links(ObjectId id) throws IOException {
        return storage.readObject(id, new ResolvedGitObjectRead<>(storage, GitObjectLinks::read));
    }
}
