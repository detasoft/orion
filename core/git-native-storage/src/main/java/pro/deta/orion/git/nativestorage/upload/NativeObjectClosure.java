package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class NativeObjectClosure {
    private static final int RAW_OBJECT_ID_BYTES = 20;

    private final LooseObjectStore objects;

    public NativeObjectClosure(LooseObjectStore objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public Set<GitObjectId> objectIdsFor(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves) {
        return selectionFor(wants, haves, 0).objectIds();
    }

    public FetchSelection selectionFor(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            int depth) {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "Fetch depth must not be negative");
        }

        FetchSelection wantedClosure = depth == 0
                ? new FetchSelection(traverse(wants, false), Set.of())
                : shallowSelection(wants, depth);
        Set<GitObjectId> objectIds =
                new LinkedHashSet<>(wantedClosure.objectIds());
        objectIds.removeAll(traverse(haves, true));

        Set<GitObjectId> shallowBoundaries =
                new LinkedHashSet<>(wantedClosure.shallowBoundaries());
        shallowBoundaries.retainAll(objectIds);

        return new FetchSelection(objectIds, shallowBoundaries);
    }

    private Set<GitObjectId> traverse(
            Set<GitObjectId> roots,
            boolean ignoreMissing) {
        Set<GitObjectId> visited = new LinkedHashSet<>();
        ArrayDeque<GitObjectId> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            GitObjectId id = pending.removeFirst();
            if (!visited.add(id)) {
                continue;
            }
            LooseObject object = objects.read(id).orElse(null);
            if (object == null) {
                if (ignoreMissing) {
                    continue;
                }
                throw missingObject();
            }
            switch (object.type()) {
                case COMMIT -> addCommitReferences(object.data(), pending);
                case TREE -> addTreeReferences(object.data(), pending);
                case TAG -> addTagReference(object.data(), pending);
                case BLOB -> {
                }
            }
        }
        return visited;
    }

    private FetchSelection shallowSelection(
            Set<GitObjectId> roots,
            int depth) {
        Set<GitObjectId> objectIds = new LinkedHashSet<>();
        Set<GitObjectId> visitedNonCommits = new LinkedHashSet<>();
        LinkedHashMap<GitObjectId, Integer> commitDepths =
                new LinkedHashMap<>();
        ArrayDeque<ShallowPendingObject> pending = new ArrayDeque<>();
        for (GitObjectId root : roots) {
            pending.addLast(new ShallowPendingObject(root, depth));
        }

        while (!pending.isEmpty()) {
            ShallowPendingObject current = pending.removeFirst();
            LooseObject object = objects.read(current.objectId())
                    .orElseThrow(NativeObjectClosure::missingObject);
            switch (object.type()) {
                case COMMIT -> addShallowCommit(
                        current,
                        object.data(),
                        objectIds,
                        commitDepths,
                        pending);
                case TREE -> addShallowNonCommit(
                        current.objectId(),
                        objectIds,
                        visitedNonCommits,
                        pending,
                        queue -> addTreeReferences(object.data(), queue));
                case TAG -> addShallowNonCommit(
                        current.objectId(),
                        objectIds,
                        visitedNonCommits,
                        pending,
                        queue -> addTagReference(object.data(), queue));
                case BLOB -> addShallowNonCommit(
                        current.objectId(),
                        objectIds,
                        visitedNonCommits,
                        pending,
                        ignored -> {
                        });
            }
        }

        return new FetchSelection(
                objectIds,
                shallowBoundaries(commitDepths.keySet()));
    }

    private static void addShallowCommit(
            ShallowPendingObject current,
            byte[] data,
            Set<GitObjectId> objectIds,
            LinkedHashMap<GitObjectId, Integer> commitDepths,
            ArrayDeque<ShallowPendingObject> pending) {
        int remainingDepth = Math.max(1, current.remainingDepth());
        Integer previousDepth = commitDepths.get(current.objectId());
        if (previousDepth != null && previousDepth >= remainingDepth) {
            return;
        }
        commitDepths.put(current.objectId(), remainingDepth);
        objectIds.add(current.objectId());

        CommitReferences references = commitReferences(data);
        pending.addLast(new ShallowPendingObject(references.tree(), 0));
        if (remainingDepth <= 1) {
            return;
        }
        for (GitObjectId parent : references.parents()) {
            pending.addLast(new ShallowPendingObject(
                    parent,
                    remainingDepth - 1));
        }
    }

    private static void addShallowNonCommit(
            GitObjectId objectId,
            Set<GitObjectId> objectIds,
            Set<GitObjectId> visitedNonCommits,
            ArrayDeque<ShallowPendingObject> pending,
            ReferenceAppender appender) {
        if (!visitedNonCommits.add(objectId)) {
            return;
        }
        objectIds.add(objectId);
        ArrayDeque<GitObjectId> references = new ArrayDeque<>();
        appender.addReferences(references);
        while (!references.isEmpty()) {
            pending.addLast(new ShallowPendingObject(
                    references.removeFirst(),
                    0));
        }
    }

    private Set<GitObjectId> shallowBoundaries(
            Set<GitObjectId> includedCommits) {
        Set<GitObjectId> boundaries = new LinkedHashSet<>();
        for (GitObjectId commitId : includedCommits) {
            LooseObject object = objects.read(commitId)
                    .orElseThrow(NativeObjectClosure::missingObject);
            CommitReferences references = commitReferences(object.data());
            for (GitObjectId parent : references.parents()) {
                if (!includedCommits.contains(parent)) {
                    boundaries.add(commitId);
                    break;
                }
            }
        }
        return boundaries;
    }

    private static GitUploadPackException missingObject() {
        return new GitUploadPackException(
                GitUploadPackException.Kind.MISSING_OBJECT,
                "Requested Git object is unavailable");
    }

    private static void addCommitReferences(
            byte[] data,
            ArrayDeque<GitObjectId> pending) {
        CommitReferences references = commitReferences(data);
        pending.addLast(references.tree());
        for (GitObjectId parent : references.parents()) {
            pending.addLast(parent);
        }
    }

    private static CommitReferences commitReferences(byte[] data) {
        GitObjectId tree = null;
        List<GitObjectId> parents = new ArrayList<>();
        String commit = new String(data, StandardCharsets.US_ASCII);
        String[] lines = commit.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith("tree ")) {
                tree = GitObjectId.of(line.substring("tree ".length()));
            } else if (line.startsWith("parent ")) {
                parents.add(GitObjectId.of(
                        line.substring("parent ".length())));
            }
        }
        if (tree == null) {
            throw new IllegalArgumentException(
                    "Malformed Git commit object");
        }
        return new CommitReferences(tree, parents);
    }

    private static void addTreeReferences(
            byte[] data,
            ArrayDeque<GitObjectId> pending) {
        int offset = 0;
        while (offset < data.length) {
            int nul = indexOf(data, (byte) 0, offset);
            if (nul < 0 || nul + 1 + RAW_OBJECT_ID_BYTES > data.length) {
                throw new IllegalArgumentException("Malformed Git tree object");
            }
            byte[] rawId = new byte[RAW_OBJECT_ID_BYTES];
            System.arraycopy(data, nul + 1, rawId, 0, RAW_OBJECT_ID_BYTES);
            pending.addLast(GitObjectId.of(HexFormat.of().formatHex(rawId)));
            offset = nul + 1 + RAW_OBJECT_ID_BYTES;
        }
    }

    private static void addTagReference(
            byte[] data,
            ArrayDeque<GitObjectId> pending) {
        byte[] prefix = "object ".getBytes(StandardCharsets.US_ASCII);
        int idLength = 40;
        int newline = prefix.length + idLength;
        if (data.length <= newline || data[newline] != '\n') {
            throw new IllegalArgumentException("Malformed Git tag object");
        }
        for (int index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                throw new IllegalArgumentException(
                        "Malformed Git tag object");
            }
        }
        String objectId = new String(
                data,
                prefix.length,
                idLength,
                StandardCharsets.US_ASCII);
        pending.addLast(GitObjectId.of(objectId));
    }

    private static int indexOf(byte[] data, byte target, int offset) {
        for (int i = offset; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    public record FetchSelection(
            Set<GitObjectId> objectIds,
            Set<GitObjectId> shallowBoundaries) {

        public FetchSelection {
            Objects.requireNonNull(objectIds, "objectIds");
            Objects.requireNonNull(
                    shallowBoundaries,
                    "shallowBoundaries");
            objectIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(objectIds));
            shallowBoundaries = Collections.unmodifiableSet(
                    new LinkedHashSet<>(shallowBoundaries));
        }
    }

    private record ShallowPendingObject(
            GitObjectId objectId,
            int remainingDepth) {
    }

    private record CommitReferences(
            GitObjectId tree,
            List<GitObjectId> parents) {

        private CommitReferences {
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(parents, "parents");
            parents = List.copyOf(parents);
        }
    }

    @FunctionalInterface
    private interface ReferenceAppender {
        void addReferences(ArrayDeque<GitObjectId> pending);
    }
}
