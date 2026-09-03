package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class NativeObjectClosure {
    private static final int RAW_OBJECT_ID_BYTES = 20;

    private final Function<GitObjectId, Optional<LooseObject>> objectReader;

    public NativeObjectClosure(LooseObjectStore objects) {
        this(Objects.requireNonNull(objects, "objects")::read);
    }

    public NativeObjectClosure(
            Function<GitObjectId, Optional<LooseObject>> objectReader) {
        this.objectReader = Objects.requireNonNull(
                objectReader,
                "objectReader");
    }

    public Set<GitObjectId> objectIdsFor(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves) {
        return selectionFor(wants, haves, 0, NativeObjectFilter.NONE)
                .objectIds();
    }

    public FetchSelection selectionFor(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            int depth) {
        return selectionFor(wants, haves, depth, NativeObjectFilter.NONE);
    }

    public FetchSelection selectionFor(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            int depth,
            NativeObjectFilter objectFilter) {
        return selectionFor(
                wants,
                haves,
                depth,
                Set.of(),
                false,
                -1,
                Set.of(),
                objectFilter);
    }

    public FetchSelection selectionFor(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            int depth,
            Set<GitObjectId> clientShallowCommits,
            boolean deepenRelative,
            long deepenSince,
            Set<GitObjectId> deepenNotRoots,
            NativeObjectFilter objectFilter) {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");
        Objects.requireNonNull(clientShallowCommits, "clientShallowCommits");
        Objects.requireNonNull(deepenNotRoots, "deepenNotRoots");
        Objects.requireNonNull(objectFilter, "objectFilter");
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "Fetch depth must not be negative");
        }
        if (deepenSince < -1) {
            throw new IllegalArgumentException(
                    "deepenSince must be absent or non-negative");
        }
        if (deepenRelative && depth == 0) {
            throw new IllegalArgumentException(
                    "deepenRelative requires a fetch depth");
        }

        FetchSelection wantedClosure = shallowSelectionRequired(
                depth,
                clientShallowCommits,
                deepenRelative,
                deepenSince,
                deepenNotRoots)
                ? shallowSelection(
                        wants,
                        depth,
                        clientShallowCommits,
                        deepenRelative,
                        deepenSince,
                        deepenNotRoots)
                : new FetchSelection(traverse(wants, false), Set.of());
        Set<GitObjectId> objectIds =
                new LinkedHashSet<>(wantedClosure.objectIds());
        objectIds.removeAll(traverse(haves, true));
        applyObjectFilter(objectIds, wants, objectFilter);

        Set<GitObjectId> shallowBoundaries =
                new LinkedHashSet<>(wantedClosure.shallowBoundaries());
        shallowBoundaries.retainAll(objectIds);

        return new FetchSelection(
                objectIds,
                shallowBoundaries,
                wantedClosure.unshallowBoundaries());
    }

    public Set<GitObjectId> existingObjectIdsReachableFrom(
            Set<GitObjectId> roots) {
        Objects.requireNonNull(roots, "roots");
        return traverse(roots, true);
    }

    public boolean allRootsReachAny(
            Iterable<GitObjectId> roots,
            Iterable<GitObjectId> candidates) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(candidates, "candidates");
        Set<GitObjectId> candidateIds = new LinkedHashSet<>();
        for (GitObjectId candidate : candidates) {
            candidateIds.add(Objects.requireNonNull(candidate, "candidate"));
        }
        if (candidateIds.isEmpty()) {
            return false;
        }
        boolean foundRoot = false;
        for (GitObjectId root : roots) {
            foundRoot = true;
            Set<GitObjectId> reachable = traverse(
                    Set.of(Objects.requireNonNull(root, "root")),
                    true);
            if (Collections.disjoint(reachable, candidateIds)) {
                return false;
            }
        }
        return foundRoot;
    }

    public boolean isAncestor(
            GitObjectId ancestor,
            GitObjectId descendant) {
        Objects.requireNonNull(ancestor, "ancestor");
        Objects.requireNonNull(descendant, "descendant");
        if (!isCommit(ancestor) || !isCommit(descendant)) {
            return false;
        }
        return commitDistances(descendant).containsKey(ancestor);
    }

    public Optional<GitObjectId> mergeBase(
            GitObjectId first,
            GitObjectId second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!isCommit(first) || !isCommit(second)) {
            return Optional.empty();
        }
        Map<GitObjectId, Integer> firstDistances = commitDistances(first);
        Map<GitObjectId, Integer> secondDistances = commitDistances(second);
        GitObjectId nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (Map.Entry<GitObjectId, Integer> entry : firstDistances.entrySet()) {
            Integer secondDistance = secondDistances.get(entry.getKey());
            if (secondDistance == null) {
                continue;
            }
            int distance = entry.getValue() + secondDistance;
            if (distance < nearestDistance
                    || distance == nearestDistance
                    && (nearest == null
                    || entry.getKey().value().compareTo(nearest.value()) < 0)) {
                nearest = entry.getKey();
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private boolean isCommit(GitObjectId objectId) {
        LooseObject object = objectReader.apply(objectId).orElse(null);
        return object != null && object.type() == ObjectType.COMMIT;
    }

    private Map<GitObjectId, Integer> commitDistances(GitObjectId root) {
        Map<GitObjectId, Integer> distances = new LinkedHashMap<>();
        ArrayDeque<CommitAtDistance> pending = new ArrayDeque<>();
        pending.addLast(new CommitAtDistance(root, 0));
        while (!pending.isEmpty()) {
            CommitAtDistance current = pending.removeFirst();
            if (distances.containsKey(current.objectId())) {
                continue;
            }
            LooseObject object = objectReader.apply(current.objectId()).orElse(null);
            if (object == null || object.type() != ObjectType.COMMIT) {
                continue;
            }
            distances.put(current.objectId(), current.distance());
            for (GitObjectId parent : commitReferences(object.data()).parents()) {
                pending.addLast(new CommitAtDistance(parent, current.distance() + 1));
            }
        }
        return distances;
    }

    private void applyObjectFilter(
            Set<GitObjectId> objectIds,
            Set<GitObjectId> directWants,
            NativeObjectFilter objectFilter) {
        if (objectFilter == NativeObjectFilter.NONE) {
            return;
        }
        for (GitObjectId id : new ArrayList<>(objectIds)) {
            if (directWants.contains(id)) {
                continue;
            }
            LooseObject object = objectReader.apply(id)
                    .orElseThrow(NativeObjectClosure::missingObject);
            if (objectFilter == NativeObjectFilter.BLOB_NONE
                    && object.type() == ObjectType.BLOB) {
                objectIds.remove(id);
            }
        }
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
            LooseObject object = objectReader.apply(id).orElse(null);
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
        return shallowSelection(
                roots,
                depth,
                Set.of(),
                false,
                -1,
                Set.of());
    }

    private FetchSelection shallowSelection(
            Set<GitObjectId> roots,
            int depth,
            Set<GitObjectId> clientShallowCommits,
            boolean deepenRelative,
            long deepenSince,
            Set<GitObjectId> deepenNotRoots) {
        Set<GitObjectId> objectIds = new LinkedHashSet<>();
        Set<GitObjectId> visitedNonCommits = new LinkedHashSet<>();
        LinkedHashMap<GitObjectId, Integer> commitDepths =
                new LinkedHashMap<>();
        Set<GitObjectId> stoppedBoundaries = new LinkedHashSet<>();
        Set<GitObjectId> deepenNotCommits = commitIdsReachableFrom(
                deepenNotRoots);
        ArrayDeque<ShallowPendingObject> pending = new ArrayDeque<>();
        for (GitObjectId root : roots) {
            pending.addLast(new ShallowPendingObject(root, depth));
        }

        while (!pending.isEmpty()) {
            ShallowPendingObject current = pending.removeFirst();
            LooseObject object = objectReader.apply(current.objectId())
                    .orElseThrow(NativeObjectClosure::missingObject);
            switch (object.type()) {
                case COMMIT -> addShallowCommit(
                        current,
                        object.data(),
                        objectIds,
                        commitDepths,
                        stoppedBoundaries,
                        pending,
                        depth,
                        clientShallowCommits,
                        deepenRelative,
                        deepenSince,
                        deepenNotCommits);
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

        Set<GitObjectId> shallowBoundaries = shallowBoundaries(
                stoppedBoundaries,
                commitDepths.keySet());
        return new FetchSelection(
                objectIds,
                shallowBoundaries,
                unshallowBoundaries(
                        clientShallowCommits,
                        commitDepths.keySet(),
                        shallowBoundaries));
    }

    private static boolean shallowSelectionRequired(
            int depth,
            Set<GitObjectId> clientShallowCommits,
            boolean deepenRelative,
            long deepenSince,
            Set<GitObjectId> deepenNotRoots) {
        return depth > 0
                || !clientShallowCommits.isEmpty()
                || deepenRelative
                || deepenSince >= 0
                || !deepenNotRoots.isEmpty();
    }

    private void addShallowCommit(
            ShallowPendingObject current,
            byte[] data,
            Set<GitObjectId> objectIds,
            LinkedHashMap<GitObjectId, Integer> commitDepths,
            Set<GitObjectId> stoppedBoundaries,
            ArrayDeque<ShallowPendingObject> pending,
            int requestedDepth,
            Set<GitObjectId> clientShallowCommits,
            boolean deepenRelative,
            long deepenSince,
            Set<GitObjectId> deepenNotCommits) {
        int remainingDepth = Math.max(1, current.remainingDepth());
        Integer previousDepth = commitDepths.get(current.objectId());
        if (previousDepth != null && previousDepth >= remainingDepth) {
            return;
        }
        commitDepths.put(current.objectId(), remainingDepth);
        objectIds.add(current.objectId());

        CommitReferences references = commitReferences(data);
        pending.addLast(new ShallowPendingObject(references.tree(), 0));
        if (shouldStopAtClientShallow(
                current.objectId(),
                clientShallowCommits,
                deepenRelative,
                deepenSince,
                deepenNotCommits,
                current.remainingDepth())) {
            stoppedBoundaries.add(current.objectId());
            return;
        }
        if (!deepenRelative
                && current.remainingDepth() > 0
                && remainingDepth <= 1
                && !references.parents().isEmpty()) {
            stoppedBoundaries.add(current.objectId());
            return;
        }
        if (deepenRelative
                && current.remainingDepth() < requestedDepth
                && current.remainingDepth() <= 1
                && !references.parents().isEmpty()) {
            stoppedBoundaries.add(current.objectId());
            return;
        }
        int nextDepth = nextDepth(
                current.objectId(),
                remainingDepth,
                clientShallowCommits,
                deepenRelative);
        for (GitObjectId parent : references.parents()) {
            if (excludedByShallowStop(
                    parent,
                    deepenSince,
                    deepenNotCommits)) {
                stoppedBoundaries.add(current.objectId());
                continue;
            }
            pending.addLast(new ShallowPendingObject(
                    parent,
                    nextDepth));
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
            Set<GitObjectId> stoppedBoundaries,
            Set<GitObjectId> includedCommits) {
        Set<GitObjectId> boundaries = new LinkedHashSet<>(stoppedBoundaries);
        for (GitObjectId commitId : includedCommits) {
            LooseObject object = objectReader.apply(commitId)
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

    private Set<GitObjectId> commitIdsReachableFrom(
            Set<GitObjectId> roots) {
        Set<GitObjectId> reachable = traverse(roots, true);
        Set<GitObjectId> commits = new LinkedHashSet<>();
        for (GitObjectId id : reachable) {
            LooseObject object = objectReader.apply(id).orElse(null);
            if (object != null && object.type() == ObjectType.COMMIT) {
                commits.add(id);
            }
        }
        return commits;
    }

    private Set<GitObjectId> unshallowBoundaries(
            Set<GitObjectId> clientShallowCommits,
            Set<GitObjectId> includedCommits,
            Set<GitObjectId> shallowBoundaries) {
        Set<GitObjectId> unshallow = new LinkedHashSet<>();
        for (GitObjectId commitId : clientShallowCommits) {
            if (!includedCommits.contains(commitId)
                    || shallowBoundaries.contains(commitId)) {
                continue;
            }
            LooseObject object = objectReader.apply(commitId).orElse(null);
            if (object == null || object.type() != ObjectType.COMMIT) {
                continue;
            }
            CommitReferences references = commitReferences(object.data());
            if (includedCommits.containsAll(references.parents())) {
                unshallow.add(commitId);
            }
        }
        return unshallow;
    }

    private boolean excludedByShallowStop(
            GitObjectId commitId,
            long deepenSince,
            Set<GitObjectId> deepenNotCommits) {
        if (deepenNotCommits.contains(commitId)) {
            return true;
        }
        if (deepenSince < 0) {
            return false;
        }
        LooseObject object = objectReader.apply(commitId)
                .orElseThrow(NativeObjectClosure::missingObject);
        if (object.type() != ObjectType.COMMIT) {
            return false;
        }
        return commitReferences(object.data()).committerTimestamp()
                <= deepenSince;
    }

    private static boolean shouldStopAtClientShallow(
            GitObjectId commitId,
            Set<GitObjectId> clientShallowCommits,
            boolean deepenRelative,
            long deepenSince,
            Set<GitObjectId> deepenNotCommits,
            int requestedDepth) {
        return clientShallowCommits.contains(commitId)
                && !deepenRelative
                && deepenSince < 0
                && deepenNotCommits.isEmpty()
                && requestedDepth == 0;
    }

    private static int nextDepth(
            GitObjectId commitId,
            int remainingDepth,
            Set<GitObjectId> clientShallowCommits,
            boolean deepenRelative) {
        if (deepenRelative && !clientShallowCommits.contains(commitId)) {
            return remainingDepth;
        }
        return Math.max(0, remainingDepth - 1);
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
        long committerTimestamp = -1;
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
            } else if (line.startsWith("committer ")) {
                committerTimestamp = parseCommitterTimestamp(line);
            }
        }
        if (tree == null || committerTimestamp < 0) {
            throw new IllegalArgumentException(
                    "Malformed Git commit object");
        }
        return new CommitReferences(tree, parents, committerTimestamp);
    }

    private static long parseCommitterTimestamp(String line) {
        int timezoneSeparator = line.lastIndexOf(' ');
        if (timezoneSeparator <= 0) {
            throw new IllegalArgumentException("Malformed Git commit object");
        }
        int timestampSeparator = line.lastIndexOf(' ', timezoneSeparator - 1);
        if (timestampSeparator <= 0) {
            throw new IllegalArgumentException("Malformed Git commit object");
        }
        try {
            return Long.parseLong(
                    line.substring(timestampSeparator + 1, timezoneSeparator));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Malformed Git commit object",
                    error);
        }
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
            Set<GitObjectId> shallowBoundaries,
            Set<GitObjectId> unshallowBoundaries) {

        public FetchSelection(
                Set<GitObjectId> objectIds,
                Set<GitObjectId> shallowBoundaries) {
            this(objectIds, shallowBoundaries, Set.of());
        }

        public FetchSelection {
            Objects.requireNonNull(objectIds, "objectIds");
            Objects.requireNonNull(
                    shallowBoundaries,
                    "shallowBoundaries");
            Objects.requireNonNull(
                    unshallowBoundaries,
                    "unshallowBoundaries");
            objectIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(objectIds));
            shallowBoundaries = Collections.unmodifiableSet(
                    new LinkedHashSet<>(shallowBoundaries));
            unshallowBoundaries = Collections.unmodifiableSet(
                    new LinkedHashSet<>(unshallowBoundaries));
        }
    }

    private record ShallowPendingObject(
            GitObjectId objectId,
            int remainingDepth) {
    }

    private record CommitAtDistance(
            GitObjectId objectId,
            int distance) {
    }

    private record CommitReferences(
            GitObjectId tree,
            List<GitObjectId> parents,
            long committerTimestamp) {

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
