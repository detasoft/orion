package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.*;
import pro.deta.orion.git.parser.v2.fetch.FetchRequest;
import pro.deta.orion.git.parser.v2.fetch.FetchTestSupport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.GitRepositoryContext.publishRefs;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;
import static pro.deta.orion.git.parser.v2.data.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.data.GitTransport.SSH;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;

class FetchCommandTest {
    @TempDir
    static Path directory;
    private static final ObjectId FIRST = PackTestData.objectId(GitObjectType.BLOB, new byte[]{1});
    private static final ObjectId SECOND = PackTestData.objectId(GitObjectType.BLOB, new byte[]{2});
    private static final RefId MAIN = new RefId("refs/heads/main");

    @Test
    void preparesParsedRequestWithoutProcessingItsNegotiationMessages() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.capabilities().add(value(GitCapability.WAIT_FOR_DONE));
        request.wants().add(PackTestData.objectId(GitObjectType.BLOB, new byte[]{1}));
        request.initialMessages().add(new NegotiationMessage.Have(PackTestData.objectId(GitObjectType.BLOB, new byte[]{2})));
        request.initialMessages().add(NegotiationMessage.Control.DONE);
        new InMemoryIndex().withAccess(Optional.of(PackId.create()), index -> {
            var storage = storage(Set.of(FIRST), index);
            var command = new FetchCommand(storage, index, capabilities(GitCapability.WAIT_FOR_DONE));

            var iterator = command.prepareNegotiation(request, HTTP);

            assertThat(iterator.getContext().request()).isSameAs(request);
            assertThat(iterator.getContext().commonObjects()).isEmpty();
            assertThat(iterator.getContext().doneReceived()).isFalse();
            assertThat(iterator.getContext().ready()).isFalse();
            assertThat(iterator.getResponsesToSend()).isEmpty();
            return null;
        });
    }

    @Test
    void rejectsUnadvertisedCapabilitiesDuringPreparation() throws Exception {
        var request = new FetchRequest();
        request.capabilities().add(value(GitCapability.THIN_PACK));
        GitStorageAccess storage = FetchTestSupport.storage(directory);
        new InMemoryIndex().withAccess(Optional.of(PackId.create()), index -> {
            var command = new FetchCommand(storage, index, capabilities());
            assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                    .isInstanceOf(IOException.class).hasMessageContaining("thin-pack");
            return null;
        });
    }

    @Test
    void rejectsUnadvertisedWantRefsBeforeReadingTheStorageSnapshot() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add("refs/heads/main");
        GitStorageAccess storage = FetchTestSupport.storage(directory);
        new InMemoryIndex().withAccess(Optional.of(PackId.create()), index -> {
            var command = new FetchCommand(storage, index, capabilities());
            assertThatThrownBy(() -> command.prepareNegotiation(request, HTTP))
                    .isInstanceOf(IOException.class).hasMessageContaining("ref-in-want");
            return null;
        });
    }

    @Test
    void separateRequestsGetIndependentNegotiationState() throws Exception {
        GitStorageAccess storage = FetchTestSupport.storage(directory);
        new InMemoryIndex().withAccess(Optional.of(PackId.create()), index -> {
            var command = new FetchCommand(storage, index, capabilities());
            var first = command.prepareNegotiation(new FetchRequest(), SSH);
            first.next(NegotiationMessage.Control.DONE);
            var second = command.prepareNegotiation(new FetchRequest(), SSH);

            assertThat(first.getContext().doneReceived()).isTrue();
            assertThat(second.getContext()).isNotSameAs(first.getContext());
            assertThat(second.getContext().doneReceived()).isFalse();
            assertThat(second.getResponsesToSend()).isEmpty();
            return null;
        });
    }

    @Test
    void defaultAccessAllowsAnExistingObjectWithoutRequiringAnAdvertisedRef() throws Exception {
        var request = new FetchRequest();
        request.wants().add(FIRST);
        new InMemoryIndex().withAccess(Optional.of(PackId.create()), index -> {
            var storage = storage(Set.of(FIRST), index);
            var command = new FetchCommand(storage, index, capabilities());
            var iterator = command.prepareNegotiation(request, SSH);
            assertThat(iterator.getContext().wantedObjects()).containsExactly(FIRST);
            return null;
        });
    }

    @Test
    void resolvesRefsAndDeduplicatesWantedObjects() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wants().add(FIRST);
        request.wantRefs().addAll(List.of(MAIN.value(), "HEAD"));
        InMemoryIndex indexApi = new InMemoryIndex();
        indexApi.withAccess(Optional.of(PackId.create()), index -> {
            var storage = storage(Set.of(FIRST), index);
            publishRefs(
                    storage, indexApi,
                    List.of(new RefUpdate(MAIN, Optional.empty(), Optional.of(FIRST))), true);
            var command = new FetchCommand(storage, index, capabilities(GitCapability.REF_IN_WANT));

            var iterator = command.prepareNegotiation(request, HTTP);

            assertThat(iterator.getContext().wantedRefs()).containsExactly(
                    Map.entry(MAIN, FIRST), Map.entry(new RefId("HEAD"), FIRST));
            return null;
        });
    }

    @Test
    void missingExplicitObjectFailsBeforeNegotiation() throws Exception {
        var request = new FetchRequest();
        request.wants().addAll(List.of(FIRST, SECOND));
        new InMemoryIndex().withAccess(Optional.of(PackId.create()), index -> {
            var storage = storage(Set.of(FIRST), index);
            var command = new FetchCommand(storage, index, capabilities());
            assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                    .isInstanceOf(IOException.class).hasMessageContaining(SECOND.toHex());
            return null;
        });
    }

    @Test
    void accessHookCanRejectResolvedWantsBeforeCheckingObjectExistence() throws Exception {
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add(MAIN.value());
        request.wants().add(SECOND);
        InMemoryIndex indexApi = new InMemoryIndex();
        indexApi.withAccess(Optional.of(PackId.create()), index -> {
            GitStorageAccess storage = storage(Set.of(FIRST), index);
            publishRefs(
                    storage, indexApi,
                    List.of(new RefUpdate(MAIN, Optional.empty(), Optional.of(FIRST))), true);
            IOException denied = new IOException("Fetch access denied");
            GitRepositoryContext repository = new GitRepositoryContext(storage, index) {
                @Override
                public void checkFetchAccess(NegotiationContext context, RefsSnapshot snapshot) throws IOException {
                    assertThat(context.request()).isSameAs(request);
                    assertThat(context.wantedObjects()).containsExactly(SECOND, FIRST);
                    assertThat(context.wantedRefs()).containsExactly(Map.entry(MAIN, FIRST));
                    assertThat(snapshot.refs()).containsEntry(MAIN, FIRST);
                    throw denied;
                }
            };
            FetchCommand command = new FetchCommand(repository, capabilities(GitCapability.REF_IN_WANT));
            assertThatThrownBy(() -> command.prepareNegotiation(request, HTTP)).isSameAs(denied);
            return null;
        });
    }

    private static GitStorageAccess storage(Set<ObjectId> ids, GitIndexAccess index) throws Exception {
        GitStorageAccess storage = FetchTestSupport.storage(directory);
        for (ObjectId id : ids) {
            PackTestData.store(storage, index, GitObjectType.BLOB, new byte[]{(byte) (id.equals(FIRST) ? 1 : 2)});
        }
        return storage;
    }
}
