package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitFileUpdate;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.command.FetchCommand;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.fetch.FetchRequest;
import pro.deta.orion.git.parser.v2.fetch.FetchNegotiatorIterator;
import pro.deta.orion.git.parser.v2.fetch.FetchPlan;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;
import static pro.deta.orion.transport.git.GitWireTestClient.*;

class DefaultGitNativeRepositoryServiceTest implements NativeGitRepositoryProvider, GitNativeRepositoryAccessHook {
    private NativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
    private NativeGitRepositoryProvider replacement;
    private final List<String> calls = new ArrayList<>();
    private final DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(this);
    private boolean publicName = true;
    private boolean rejectRead;
    private boolean rejectReceive;
    private boolean rejectUpdates;
    private boolean rejectUnresolvedFetch;
    private boolean rejectFetch;
    private boolean rejectPublication;
    private int lookups;
    private int publishCalls;

    @Test
    void rejectsInternalRepositoryBeforeLookupOrCreation() {
        publicName = false;
        for (InitialRequestData request : List.of(request("demo"), receiveRequest("demo"))) {
            assertThatThrownBy(() -> service.open(request, this)).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(lookups).isZero();
        assertThat(calls).isEmpty();
        assertThat(backend.exists("demo")).isFalse();
    }

    @Test
    void reportsMissingReadRepositoryWithoutCreatingIt() {
        assertThatThrownBy(() -> service.open(request("demo"), this))
                .isInstanceOf(IOException.class).hasMessageContaining("does not exist");
        assertThat(backend.exists("demo")).isFalse();
        assertThat(calls).containsExactly("read demo");
    }

    @Test
    void opensForReadOnceAfterAuthorization() throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        GitRepositoryContext context = service.open(request("demo"), this);
        assertThat(context.storage()).isSameAs(repository.storage());
        assertThat(lookups).isEqualTo(1);
        assertThat(calls).containsExactly("read demo");
    }

    @Test
    void rejectedReadDoesNotReachProvider() {
        rejectRead = true;
        assertThatThrownBy(() -> service.open(request("demo"), this)).isInstanceOf(AccessDeniedException.class);
        assertThat(lookups).isZero();
        assertThat(calls).containsExactly("read demo");
    }

    @Test
    void createsReceiveRepositoryAfterReceiveAndCreateHooks() throws Exception {
        service.open(receiveRequest("demo"), this);
        assertThat(backend.exists("demo")).isTrue();
        assertThat(calls).containsExactly("receive demo", "create demo");
    }

    @Test
    void opensExistingReceiveRepositoryAfterReceiveAndWriteHooks() throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        assertThat(service.open(receiveRequest("demo"), this).storage()).isSameAs(repository.storage());
        assertThat(calls).containsExactly("receive demo", "write demo");
    }

    @Test
    void rejectedReceiveDoesNotLookUpOrCreateRepository() {
        rejectReceive = true;
        assertThatThrownBy(() -> service.open(receiveRequest("demo"), this)).isInstanceOf(AccessDeniedException.class);
        assertThat(lookups).isZero();
        assertThat(backend.exists("demo")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesIndependentUpdatesAndAbortsAtomicGroupOnStaleRef(boolean atomic) throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        NativeGitFileUpdate prepared = repository.prepareFileUpdate("feature", Map.of("a", new byte[]{1}),
                "update", GitCommitAuthor.EMPTY);
        RefUpdate feature = prepared.refUpdates().getFirst();
        GitRepositoryContext context = service.open(receiveRequest("demo"), this);
        List<RefUpdateResult> results;
        IndexedPack pack = ingest(repository, prepared.pack());
        results = context.publish(Optional.of(pack), List.of(
                RefUpdate.fromWire("refs/heads/main", TAG_ID, NULL_ID), feature), atomic);
        assertThat(results).extracting(RefUpdateResult::status)
                .containsExactly(EXPECTED_OLD_MISMATCH, atomic ? ATOMIC_ABORTED : APPLIED);
        assertThat(repository.refs()).containsEntry("refs/heads/main", MAIN_ID);
        assertThat(repository.refs().containsKey("refs/heads/feature")).isEqualTo(!atomic);
        assertThat(repository.storage().exists(feature.newId().orElseThrow())).isTrue();
    }

    @Test
    void keepsPersistedPackWhenAtomicRefTransactionIsStale(@TempDir Path directory) throws Exception {
        backend = new FileNativeGitRepositoryProvider(directory);
        NativeGitRepository repository = createRepository(backend, "demo");
        repository.saveFiles("main", Map.of("a", new byte[]{0}), "initial", GitCommitAuthor.EMPTY);
        String initial = repository.refs().get("refs/heads/main");
        NativeGitFileUpdate update = repository.prepareFileUpdate("main", Map.of("a", new byte[]{1}),
                "update", GitCommitAuthor.EMPTY);
        GitRepositoryContext context = service.open(receiveRequest("demo"), this);
        List<RefUpdateResult> results;
        IndexedPack pack = ingest(repository, update.pack());
        results = context.publish(Optional.of(pack), List.of(RefUpdate.fromWire(
                "refs/heads/main", TAG_ID, update.refUpdates().getFirst().newId().orElseThrow().toHex())), true);
        assertThat(results).extracting(RefUpdateResult::status).containsExactly(EXPECTED_OLD_MISMATCH);
        NativeGitRepository reopened = new FileNativeGitRepositoryProvider(directory).find("demo")
                .valueOrFailure("repository");
        assertThat(reopened.refs()).containsEntry("refs/heads/main", initial);
        assertThat(reopened.storage().exists(update.refUpdates().getFirst().newId().orElseThrow())).isTrue();
    }

    @Test
    void publishesThroughProviderAndRetainsProviderRejection() throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        rejectPublication = true;
        NativeGitFileUpdate update = repository.prepareFileUpdate("main", Map.of("a", new byte[]{1}),
                "update", GitCommitAuthor.EMPTY);
        GitRepositoryContext context = service.open(receiveRequest("demo"), this);
        List<RefUpdateResult> results;
        IndexedPack pack = ingest(repository, update.pack());
        results = context.publish(Optional.of(pack), update.refUpdates(), true);
        assertThat(publishCalls).isEqualTo(1);
        assertThat(results).extracting(RefUpdateResult::status).containsExactly(EXPECTED_OLD_MISMATCH);
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void retainsRepositoryOpenedBeforeRefAuthorization() throws Exception {
        NativeGitRepository original = createRepository(backend, "demo");
        replacement = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository other = createRepository(replacement, "demo");
        GitRepositoryContext context = service.open(receiveRequest("demo"), this);
        List<RefUpdateResult> results = context.publish(Optional.empty(),
                List.of(RefUpdate.fromWire("refs/heads/main", NULL_ID, MAIN_ID)), true);
        assertThat(results).extracting(RefUpdateResult::status).containsExactly(APPLIED);
        assertThat(original.refs()).containsEntry("refs/heads/main", MAIN_ID);
        assertThat(other.refs()).isEmpty();
    }

    @Test
    void deniesUnauthorizedRefWithoutPublishingIt() throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        rejectUpdates = true;
        GitRepositoryContext context = service.open(receiveRequest("demo"), this);
        List<RefUpdateResult> results = context.publish(Optional.empty(),
                List.of(RefUpdate.fromWire("refs/heads/feature", NULL_ID, MAIN_ID)), false);
        assertThat(results).extracting(RefUpdateResult::status).containsExactly(REJECTED);
        assertThat(repository.refs()).isEmpty();
        assertThat(calls).contains("update demo refs/heads/feature false");
    }

    @Test
    void distinguishesFastForwardAndForcedUpdatesForAuthorization() throws Exception {
        NativeGitRepository repository = backend.create("demo").valueOrFailure("repository");
        repository.saveFiles("main", Map.of("a", new byte[]{1}), "first", GitCommitAuthor.EMPTY);
        String first = repository.refs().get("refs/heads/main");
        NativeGitFileUpdate next = repository.prepareFileUpdate("main", Map.of("a", new byte[]{2}),
                "next", GitCommitAuthor.EMPTY);
        GitRepositoryContext context = service.open(receiveRequest("demo"), this);
        IndexedPack pack = ingest(repository, next.pack());
        assertThat(context.publish(Optional.of(pack), next.refUpdates(), true))
                .extracting(RefUpdateResult::status).containsExactly(APPLIED);
        assertThat(calls).contains("update demo refs/heads/main false");
        calls.clear();
        assertThat(context.publish(Optional.empty(), List.of(RefUpdate.fromWire("refs/heads/main",
                next.refUpdates().getFirst().newId().orElseThrow().toHex(), first)), true))
                .extracting(RefUpdateResult::status).containsExactly(APPLIED);
        assertThat(calls).containsExactly("update demo refs/heads/main true");
    }

    @Test
    void rejectsRefWhoseCommitHasMissingTree() throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        String content = "tree " + "f".repeat(40) + "\nauthor A <a@test> 0 +0000\n"
                + "committer A <a@test> 0 +0000\n\nmissing tree\n";
        ObjectId commit = repository.writeObject(GitObjectType.COMMIT, content.getBytes(StandardCharsets.US_ASCII));
        List<RefUpdateResult> results = service.open(receiveRequest("demo"), this).publish(Optional.empty(),
                List.of(RefUpdate.fromWire("refs/heads/main", NULL_ID, commit.toHex())), true);
        assertThat(results).extracting(RefUpdateResult::status).containsExactly(OBJECT_NOT_FOUND);
        assertThat(repository.refs()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorizesEachWantAgainstItsReachableBranches(boolean v2) throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        repository.updateRef("refs/heads/feature", NULL_ID, MAIN_ID);
        repository.updateRef("refs/heads/tag", NULL_ID, TAG_ID);
        FetchRequest request = new FetchRequest();
        request.setMode(v2 ? FetchRequest.Mode.PROTOCOL_V2 : FetchRequest.Mode.SINGLE_ACK);
        request.wants().add(new ObjectId(MAIN_ID));
        request.wants().add(new ObjectId(TAG_ID));
        prepareFetch(service.open(request("demo"), this), request);
        assertThat(calls).containsExactly("read demo", "fetch demo [feature, main]", "fetch demo [tag]");
    }

    @Test
    void authorizesWantedRefAndReportsUnresolvedV2Want() throws Exception {
        NativeGitRepository repository = createRepository(backend, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add("HEAD");
        GitRepositoryContext context = service.open(request("demo"), this);
        prepareFetch(context, request);
        assertThat(calls).contains("fetch demo [main]");
        request.wantRefs().clear();
        request.wants().add(new ObjectId("f".repeat(40)));
        rejectUnresolvedFetch = true;
        assertThatThrownBy(() -> prepareFetch(context, request)).isInstanceOf(AccessDeniedException.class);
        assertThat(calls).contains("fetch demo []");
    }

    @ParameterizedTest
    @CsvSource({"refs/tags/release,false", "HEAD,false", "HEAD,true"})
    void keepsAuthorizedTargetWhenWantedRefMovesDuringAccessCheck(String wantedRef, boolean detached)
            throws Exception {
        NativeGitRepository repository = backend.create("demo").valueOrFailure("repository");
        repository.saveFiles("main", Map.of("a", new byte[]{1}), "allowed", GitCommitAuthor.EMPTY);
        repository.saveFiles("secret", Map.of("a", new byte[]{2}), "denied", GitCommitAuthor.EMPTY);
        ObjectId allowed = new ObjectId(repository.refs().get("refs/heads/main"));
        ObjectId denied = new ObjectId(repository.refs().get("refs/heads/secret"));
        if (!wantedRef.equals("HEAD")) {
            assertThat(repository.updateRef(wantedRef, NULL_ID, allowed.toHex()).status()).isEqualTo(APPLIED);
        } else if (detached) {
            repository.storage().updateHead(new Head.Detached(new CommitId(allowed.toBytes())));
        }
        List<List<String>> checkedBranches = new ArrayList<>();
        GitNativeRepositoryAccessHook hook = new GitNativeRepositoryAccessHook() {
            @Override
            public void beforeFetch(String name, List<String> branches) {
                checkedBranches.add(branches);
                if (!branches.equals(List.of("main"))) {
                    throw new AccessDeniedException("denied branch", null);
                }
                if (wantedRef.equals("HEAD")) {
                    Head next = detached ? new Head.Detached(new CommitId(denied.toBytes()))
                            : new Head.Symbolic(new RefId("refs/heads/secret"));
                    assertThatCode(() -> repository.storage().updateHead(next)).doesNotThrowAnyException();
                } else {
                    assertThat(repository.updateRef(wantedRef, allowed.toHex(), denied.toHex()).status())
                            .isEqualTo(APPLIED);
                }
            }
        };
        GitRepositoryContext context = service.open(request("demo"), hook);
        GitCapabilities capabilities = new GitCapabilities(List.of(
                GitCapabilityValue.value(GitCapability.REF_IN_WANT)));
        FetchCommand command = new FetchCommand(context, capabilities);
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add(wantedRef);

        FetchNegotiatorIterator iterator = command.prepareNegotiation(request, GitTransport.HTTP);
        iterator.next(NegotiationMessage.Control.DONE);
        FetchPlan plan = command.prepareResponse(iterator.getContext()).orElseThrow();

        assertThat(checkedBranches).containsExactly(List.of("main"));
        assertThat(plan.wantedObjects()).containsExactly(allowed);
        assertThat(plan.wantedRefs()).containsExactly(Map.entry(new RefId(wantedRef), allowed));
        assertThatThrownBy(() -> command.prepareNegotiation(request, GitTransport.HTTP))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("denied branch");
        assertThat(checkedBranches).containsExactly(List.of("main"), List.of("secret"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorizesNestedTagByItsTargetAndPreservesAccessDenial(boolean v2) throws Exception {
        NativeGitRepository repository = backend.create("demo").valueOrFailure("repository");
        repository.saveFiles("main", Map.of("a", new byte[]{1}), "initial", GitCommitAuthor.EMPTY);
        String target = repository.refs().get("refs/heads/main");
        ObjectId inner = repository.writeObject(GitObjectType.TAG,
                ("object " + target + "\ntype commit\ntag inner\n\nmessage\n")
                        .getBytes(StandardCharsets.US_ASCII));
        ObjectId outer = repository.writeObject(GitObjectType.TAG,
                ("object " + inner + "\ntype tag\ntag nested\n\nmessage\n")
                        .getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/tags/inner", NULL_ID, inner.toHex());
        repository.updateRef("refs/tags/nested", NULL_ID, outer.toHex());
        FetchRequest request = new FetchRequest();
        request.setMode(v2 ? FetchRequest.Mode.PROTOCOL_V2 : FetchRequest.Mode.SINGLE_ACK);
        GitRepositoryContext context = service.open(request("demo"), this);
        for (ObjectId tag : List.of(inner, outer)) {
            request.wants().clear();
            request.wants().add(tag);
            calls.clear();
            prepareFetch(context, request);
            assertThat(calls).containsExactly("fetch demo [main]");
            rejectFetch = true;
            assertThatThrownBy(() -> prepareFetch(context, request)).isInstanceOf(AccessDeniedException.class);
            rejectFetch = false;
        }
        repository.updateRef("refs/heads/main", target, NULL_ID);
        rejectUnresolvedFetch = true;
        calls.clear();
        assertThatThrownBy(() -> prepareFetch(context, request)).isInstanceOf(AccessDeniedException.class);
        assertThat(calls).containsExactly("fetch demo []");
    }

    @Test
    void rejectsUnadvertisedLegacyWant() throws Exception {
        createRepository(backend, "demo");
        GitRepositoryContext context = service.open(request("demo"), this);
        FetchRequest request = new FetchRequest();
        request.wants().add(new ObjectId(MAIN_ID));
        assertThatThrownBy(() -> prepareFetch(context, request)).isInstanceOf(IOException.class)
                .hasMessageContaining("not an advertised object");
    }

    @ParameterizedTest
    @CsvSource({"false,main", "true,main", "false,tag", "false,HEAD"})
    void authorizesReachableBlobsAndPreservesAccessDenial(boolean v2, String ref) throws Exception {
        NativeGitRepository repository = backend.create("demo").valueOrFailure("repository");
        repository.saveFiles("main", Map.of("a", new byte[]{1}), "initial", GitCommitAuthor.EMPTY);
        if (!ref.equals("main")) {
            String tip = repository.refs().get("refs/heads/main");
            if (ref.equals("tag")) {
                repository.updateRef("refs/tags/release", NULL_ID, tip);
            } else {
                repository.storage().updateHead(new Head.Detached(new CommitId(tip)));
            }
            repository.updateRef("refs/heads/main", tip, NULL_ID);
        }
        ObjectId blob = repository.writeObject(GitObjectType.BLOB, new byte[]{1});
        FetchRequest request = new FetchRequest();
        request.setMode(v2 ? FetchRequest.Mode.PROTOCOL_V2 : FetchRequest.Mode.SINGLE_ACK);
        request.wants().add(blob);
        GitRepositoryContext context = service.open(request("demo"), this);
        String expected = ref.equals("main") ? "fetch demo [main]" : "fetch demo []";
        calls.clear();
        prepareFetch(context, request);
        assertThat(calls).containsExactly(expected);
        rejectFetch = true;
        calls.clear();
        assertThatThrownBy(() -> prepareFetch(context, request)).isInstanceOf(AccessDeniedException.class);
        assertThat(calls).containsExactly(expected);
    }

    private static FetchNegotiatorIterator prepareFetch(GitRepositoryContext context, FetchRequest request)
            throws IOException {
        GitCapabilities capabilities = new GitCapabilities(List.of(
                GitCapabilityValue.value(GitCapability.REF_IN_WANT)));
        return new FetchCommand(context, capabilities).prepareNegotiation(request, GitTransport.HTTP);
    }

    private static IndexedPack ingest(NativeGitRepository repository, byte[] bytes) throws IOException {
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            return repository.ingest(input);
        }
    }

    @Override
    public boolean isPublicRepositoryName(String name) {
        return publicName;
    }

    @Override
    public boolean exists(String name) {
        lookups++;
        return backend.exists(name);
    }

    @Override
    public Result<NativeGitRepository> find(String name) {
        lookups++;
        return backend.find(name);
    }

    @Override
    public Result<NativeGitRepository> create(String name) {
        return backend.create(name);
    }

    @Override
    public List<RefUpdateResult> publish(NativeGitRepository repository, Optional<PackId> received,
            List<RefUpdate> updates, boolean atomic) {
        publishCalls++;
        if (!rejectPublication) {
            return NativeGitRepositoryProvider.super.publish(repository, received, updates, atomic);
        }
        List<RefUpdateResult> results = new ArrayList<>();
        for (RefUpdate update : updates) {
            results.add(new RefUpdateResult(update, EXPECTED_OLD_MISMATCH, Optional.empty()));
        }
        return results;
    }

    @Override
    public void beforeRead(String name) {
        calls.add("read " + name);
        if (rejectRead) {
            throw new AccessDeniedException("denied read", null);
        }
    }

    @Override
    public void beforeReceive(String name) {
        calls.add("receive " + name);
        if (rejectReceive) {
            throw new AccessDeniedException("denied receive", null);
        }
    }

    @Override
    public void beforeCreate(String name) {
        calls.add("create " + name);
    }

    @Override
    public void beforeWrite(String name) {
        calls.add("write " + name);
    }

    @Override
    public void beforeFetch(String name, List<String> branches) {
        calls.add("fetch " + name + " " + branches);
        if (rejectFetch || rejectUnresolvedFetch && branches.isEmpty()) {
            throw new AccessDeniedException("unresolved want", null);
        }
    }

    @Override
    public void beforeUpdate(String name, String ref, boolean force) {
        calls.add("update " + name + " " + ref + " " + force);
        if (replacement != null) {
            backend = replacement;
        }
        if (rejectUpdates) {
            throw new AccessDeniedException("denied update", null);
        }
    }
}
