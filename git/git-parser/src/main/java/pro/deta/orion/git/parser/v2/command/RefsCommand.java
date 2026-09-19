package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.read.GitObjectLinks;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsArgument;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class RefsCommand implements GitCommand {
    private static final RefId HEAD = new RefId("HEAD");
    private final GitStorageApi storage;
    private final boolean unbornAllowed;

    public RefsCommand(GitStorageApi storage, GitCapabilities advertisedCapabilities) {
        this.storage = Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(advertisedCapabilities, "advertisedCapabilities");
        unbornAllowed = List.of(advertisedCapabilities.value(GitCapability.LS_REFS).orElse("").split(" "))
                .contains(LsRefsArgument.UNBORN.wireName());
    }

    @Override
    public void action(GitProtocolContext protocolContext) throws IOException {
        if (protocolContext.version() != GitProtocolVersion.V2) {
            throw new IOException("ls-refs requires protocol v2");
        }
        LsRefsRequest request = LsRefsRequest.parse(protocolContext.reader());
        if (request.unborn() && !unbornAllowed) {
            throw new IOException("ls-refs unborn was not advertised");
        }
        RefsSnapshot snapshot = storage.snapshotRefs();
        GitProtocolContext.Writer writer = protocolContext.writer();
        if (request.matches(HEAD.value())) {
            writeHead(snapshot, request, writer);
        }
        List<RefId> names = new ArrayList<>(snapshot.refs().keySet());
        names.sort(Comparator.comparing(RefId::value));
        for (RefId name : names) {
            if (request.matches(name.value())) {
                ObjectId id = snapshot.refs().get(name);
                writer.writeRef(name, Optional.of(id), Optional.empty(),
                        request.peel() ? peel(id) : Optional.empty());
            }
        }
        writer.endRefs();
        writer.flush();
    }

    private void writeHead(RefsSnapshot snapshot, LsRefsRequest request, GitProtocolContext.Writer writer)
            throws IOException {
        ObjectId id;
        Optional<RefId> symbolic = Optional.empty();
        if (snapshot.head() instanceof Head.Symbolic head) {
            id = snapshot.refs().get(head.target());
            if (request.symrefs()) {
                symbolic = Optional.of(head.target());
            }
            if (id == null && (!request.unborn() || !request.symrefs())) {
                return;
            }
        } else {
            Head.Detached head = (Head.Detached) snapshot.head();
            id = new ObjectId(head.target().toBytes());
        }
        writer.writeRef(HEAD, Optional.ofNullable(id), symbolic,
                request.peel() && id != null ? peel(id) : Optional.empty());
    }

    private Optional<ObjectId> peel(ObjectId id) throws IOException {
        Set<ObjectId> visited = new HashSet<>();
        ObjectId current = id;
        ResolvedGitObjectRead<GitObjectLinks> reader = new ResolvedGitObjectRead<>(storage,
                (type, size, base, input) -> type == GitObjectType.TAG
                        ? GitObjectLinks.read(type, size, base, input) : new GitObjectLinks(type, List.of()));
        while (visited.add(current)) {
            ObjectId object = current;
            GitObjectLinks links = storage.readObject(object, (type, size, base, input) -> switch (type) {
                case TAG, REF_DELTA, OFS_DELTA -> reader.read(type, size, base, input);
                default -> new GitObjectLinks(type, List.of());
            }).orElseThrow(() -> new IOException("Missing object while peeling ref: " + object));
            if (links.type() != GitObjectType.TAG) {
                return current.equals(id) ? Optional.empty() : Optional.of(current);
            }
            current = links.targets().getFirst();
        }
        throw new IOException("Cyclic tag while peeling ref: " + current);
    }
}
