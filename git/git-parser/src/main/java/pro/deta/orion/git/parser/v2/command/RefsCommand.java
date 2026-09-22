package pro.deta.orion.git.parser.v2.command;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsArgument;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsRequest;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.git.parser.v2.read.GitObjectGraph;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RefsCommand implements GitCommand {
    private static final RefId HEAD = new RefId("HEAD");
    private final GitStorageApi storage;
    private final GitObjectGraph graph;
    private final boolean unbornAllowed;

    public RefsCommand(GitStorageApi storage, GitCapabilities advertisedCapabilities) {
        this.storage = Objects.requireNonNull(storage, "storage");
        graph = new GitObjectGraph(storage);
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
                        request.peel() ? graph.peel(id) : Optional.empty());
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
                request.peel() && id != null ? graph.peel(id) : Optional.empty());
    }
}
