package pro.deta.orion.transport.git;

import pro.deta.orion.git.fileapi.GitCommitMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.ObjectId.CommitId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.util.Result;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.orion.v2.GrantAddress;
import pro.deta.orion.schema.orion.v2.GrantId;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.RoleId;
import pro.deta.orion.schema.orion.v2.ScopedGrant;
import pro.deta.orion.schema.orion.v2.ScopedRole;
import pro.deta.orion.schema.orion.v2.TeamId;
import pro.deta.orion.transport.git.auth.AuthenticatedRepositoryAccessHook;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import pro.deta.orion.test.integration.git.FileTestSupport;

class GitBlockingWireSessionTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private static final String MAIN_ID = "88d050b1908057b53d38b42702ebc659e3d7f696";
    private static final String WANT = "2".repeat(40);
    private static final String NULL_ID = "0".repeat(40);

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void includeTagUsesVisibleRefsForLegacyAndV2Fetches(boolean version2, boolean unrestricted) throws Exception {
        try (NativeGitRepositoryProvider provider = providerWithVisibilityRefs()) {
            NativeGitRepository source = provider.find("project").valueOrFailure("repository");
            String annotated = source.refs().get("refs/tags/annotated");
            ObjectId nested = source.writeObject(GitObjectType.TAG,
                    ("object " + annotated + "\ntype tag\ntag nested\n\nprivate annotation\n")
                            .getBytes(StandardCharsets.UTF_8));
            source.updateRef("refs/tags/nested", NULL_ID, nested.toHex());
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            try (QueueByteSource input = new QueueByteSource(Duration.ofSeconds(1))) {
                input.feed(version2 ? fetchRequest("want " + MAIN_ID + "\n", "include-tag\n", "done\n")
                        : legacyUploadRequest("want " + MAIN_ID + " include-tag\n", "done\n"));
                input.end();
                session(input, output, provider, visibilityHook(unrestricted ? null : "main"))
                        .serveSmartHttpPost(version2 ? uploadV2Request() : uploadV1Request());
            }
            byte[] response = output.bytes();
            byte[] pack;
            if (version2) {
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(response))) {
                    Optional<GitPktLine> packet;
                    while ((packet = GitPktLine.readNextFrom(input)).isPresent()) {
                        if (packet.orElseThrow() instanceof GitPktLine.Data data
                                && data.content().length > 0 && data.content()[0] == 1) {
                            received.write(data.content(), 1, data.content().length - 1);
                        }
                    }
                }
                pack = received.toByteArray();
            } else {
                int offset = output.ascii().indexOf("PACK");
                assertThat(offset).isNotNegative();
                pack = Arrays.copyOfRange(response, offset, response.length);
            }
            NativeGitRepository downloaded = provider.create("downloaded").valueOrFailure("download repository");
            downloaded.publishPack(pack, List.of(), false, GitNativeRepositoryAccessHook.ALLOW_ALL);
            assertThat(downloaded.readObject(new ObjectId(MAIN_ID))).isPresent();
            assertThat(downloaded.readObject(new ObjectId(annotated)).isPresent()).isEqualTo(unrestricted);
            assertThat(downloaded.readObject(nested).isPresent()).isEqualTo(unrestricted);
            assertThat(downloaded.readObject(new ObjectId(source.refs().get("refs/tags/secret")))).isEmpty();
        }
    }

    @Test
    void advertisementsHideDeniedBranchNamesAndAllTagsForRestrictedReaders() throws Exception {
        NativeGitRepositoryProvider provider = providerWithVisibilityRefs();
        NativeGitRepository repository = provider.find("project").valueOrFailure("repository");
        GitNativeRepositoryAccessHook reader = visibilityHook("main");
        for (InitialRequestData request : List.of(uploadV1Request(), explicitUploadV1Request(),
                receiveV1Request(), explicitReceiveV1Request(), uploadV2Request())) {
            String response = refAdvertisement(provider, reader, request, false);
            assertThat(response).contains(MAIN_ID + " refs/heads/main", MAIN_ID + " HEAD")
                    .doesNotContain("refs/heads/secret", "refs/heads/shared", "refs/tags/", "refs/internal/");
            assertThat(response).doesNotContain(repository.refs().get("refs/heads/secret"),
                    repository.refs().get("refs/tags/annotated"), repository.refs().get("refs/tags/secret"));
        }
        assertThat(refAdvertisement(provider, reader, uploadV2Request(), true))
                .contains(MAIN_ID + " refs/heads/main", "HEAD symref-target:refs/heads/main")
                .doesNotContain("refs/heads/secret", "refs/heads/shared", "refs/tags/", "refs/internal/");
    }

    @Test
    void unrestrictedAdvertisementsKeepBranchesTagsAndPeeledTargets() throws Exception {
        NativeGitRepositoryProvider provider = providerWithVisibilityRefs();
        GitNativeRepositoryAccessHook reader = visibilityHook(null);
        for (InitialRequestData request : List.of(uploadV1Request(), uploadV2Request(), receiveV1Request())) {
            String response = refAdvertisement(provider, reader, request, false);
            assertThat(response).contains("refs/heads/main", "refs/heads/secret", "refs/heads/shared",
                    "refs/tags/lightweight", "refs/tags/annotated", "refs/tags/secret");
            if (request.service() == InitialRequestService.UPLOAD_PACK) {
                assertThat(response).contains(request.parameters().get("version") != null
                        ? "peeled:" + MAIN_ID : MAIN_ID + " refs/tags/annotated^{}");
            }
        }
    }

    @Test
    void organizationBranchDenyFiltersBothProtocolVersionsAndReceiveDiscovery() throws Exception {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository = provider.create("acme/team/project").valueOrFailure("repository");
        ObjectId id = repository.writeObject(GitObjectType.BLOB, "shared".getBytes(StandardCharsets.UTF_8));
        for (String ref : List.of("refs/heads/main", "refs/heads/secret", "refs/tags/lightweight")) {
            repository.updateRef(ref, NULL_ID, id.toHex());
        }
        setHead(repository, new Head.Symbolic(new RefId("refs/heads/secret")));
        ScopedGrant allow = new ScopedGrant(new GrantId("read"), ScopedGrant.Effect.ALLOW,
                List.of(new GrantExpression(GrantKey.READ_WRITE, "true"),
                        new GrantExpression(GrantKey.BRANCH, "*")));
        ScopedGrant deny = new ScopedGrant(new GrantId("deny"), ScopedGrant.Effect.DENY,
                List.of(new GrantExpression(GrantKey.READ, "true"),
                        new GrantExpression(GrantKey.BRANCH, "secret")));
        ScopedRole role = new ScopedRole(new RoleId("reader"), List.of(),
                List.of(GrantAddress.parse("acme/read"), GrantAddress.parse("acme/deny")));
        User user = new User("reader", null, null, null, List.of(), List.of("acme/reader"), List.of());
        OrionDocument.Repository configured = new OrionDocument.Repository("project", "", "refs/heads/main",
                List.of(), List.of(), List.of(), List.of(), Optional.empty());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                List.of(configured));
        OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("acme"), "",
                List.of(user), List.of(allow, deny), List.of(role), List.of(team),
                List.of(), List.of(), List.of(), List.of());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(organization));
        GitNativeRepositoryAccessHook hook = new AuthenticatedRepositoryAccessHook(
                SecurityContext.createContext().withUserIdentity(
                        new InternalUserImpl("reader", organization.id(), () -> document)));

        for (InitialRequestData initial : List.of(uploadV1Request(), uploadV2Request(), receiveV1Request())) {
            InitialRequestData request = new InitialRequestData(initial.service(), "acme/team/project",
                    initial.host(), initial.parameters());
            assertThat(refAdvertisement(provider, hook, request, false))
                    .contains(id + " refs/heads/main")
                    .doesNotContain("refs/heads/secret", "refs/tags/", " HEAD", "symref=");
        }
    }

    @Test
    void symbolicHeadOfHiddenBranchStaysHiddenEvenWhenItsObjectIsReadable() throws Exception {
        NativeGitRepositoryProvider provider = providerWithVisibilityRefs();
        NativeGitRepository repository = provider.find("project").valueOrFailure("repository");
        setHead(repository, new Head.Symbolic(new RefId("refs/heads/shared")));
        for (InitialRequestData request : List.of(uploadV1Request(), uploadV2Request(), receiveV1Request())) {
            assertThat(refAdvertisement(provider, visibilityHook("main"), request, false))
                    .contains(MAIN_ID + " refs/heads/main")
                    .doesNotContain(" HEAD", "symref=", "symref-target:", "refs/heads/shared");
        }
    }

    @Test
    void unbornHeadUsesItsBranchPermissionAndPrefixesCannotRevealHiddenRefs() throws Exception {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        provider.create("project").valueOrFailure("repository");
        assertThat(refAdvertisement(provider, visibilityHook("main"), uploadV2Request(), false))
                .contains("unborn HEAD symref-target:refs/heads/main");
        assertThat(refAdvertisement(provider, visibilityHook("dev"), uploadV2Request(), false))
                .isEqualTo("0000");
        assertThat(refAdvertisement(provider, visibilityHook("dev"), uploadV1Request(), false))
                .contains(NULL_ID + " capabilities^{}").doesNotContain("HEAD", "refs/heads/main");

        NativeGitRepositoryProvider populated = providerWithVisibilityRefs();
        assertThat(refAdvertisement(populated, visibilityHook("main"), uploadV2Request(), false,
                "ref-prefix refs/tags/", "ref-prefix refs/heads/secret"))
                .isEqualTo("0000");
        assertThat(refAdvertisement(populated, visibilityHook("main"), uploadV2Request(), false,
                "ref-prefix refs/heads/main"))
                .contains(MAIN_ID + " refs/heads/main").doesNotContain(" HEAD", "refs/tags/");
    }

    @Test
    void detachedHeadRequiresReachabilityFromAReadableBranch() throws Exception {
        NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository = provider.create("project").valueOrFailure("repository");
        ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
        ObjectId ancestor = visibilityCommit(repository, tree, Optional.empty(), "ancestor");
        ObjectId tip = visibilityCommit(repository, tree, Optional.of(ancestor), "tip");
        ObjectId secret = visibilityCommit(repository, tree, Optional.empty(), "secret");
        repository.updateRef("refs/heads/main", NULL_ID, tip.toHex());
        repository.updateRef("refs/heads/secret", NULL_ID, secret.toHex());
        for (InitialRequestData request : List.of(uploadV1Request(), uploadV2Request(), receiveV1Request())) {
            setHead(repository, new Head.Detached(new CommitId(ancestor.toBytes())));
            assertThat(refAdvertisement(provider, visibilityHook("main"), request, false))
                    .contains(ancestor + " HEAD", tip + " refs/heads/main").doesNotContain(secret.toHex());
            setHead(repository, new Head.Detached(new CommitId(secret.toBytes())));
            assertThat(refAdvertisement(provider, visibilityHook("main"), request, false))
                    .contains(tip + " refs/heads/main").doesNotContain(" HEAD", secret.toHex());
        }
    }

    private NativeGitRepositoryProvider providerWithVisibilityRefs() {
        NativeGitRepositoryProvider provider = providerWithMainRef();
        NativeGitRepository repository = provider.find("project").valueOrFailure("repository");
        ObjectId secret = repository.writeObject(GitObjectType.BLOB, "secret".getBytes(StandardCharsets.UTF_8));
        repository.updateRef("refs/heads/secret", NULL_ID, secret.toHex());
        repository.updateRef("refs/heads/shared", NULL_ID, MAIN_ID);
        repository.updateRef("refs/tags/lightweight", NULL_ID, MAIN_ID);
        for (String tag : List.of("annotated", "secret")) {
            String target = tag.equals("secret") ? secret.toHex() : MAIN_ID;
            ObjectId id = repository.writeObject(GitObjectType.TAG,
                    ("object " + target + "\ntype blob\ntag " + tag + "\n\nmessage\n")
                            .getBytes(StandardCharsets.UTF_8));
            repository.updateRef("refs/tags/" + tag, NULL_ID, id.toHex());
        }
        repository.updateRef("refs/internal/secret", NULL_ID, secret.toHex());
        return provider;
    }

    private static ObjectId visibilityCommit(NativeGitRepository repository, ObjectId tree,
            Optional<ObjectId> parent, String message) {
        String contents = "tree " + tree + "\n" + parent.map(id -> "parent " + id + "\n").orElse("")
                + "author Reader <reader@example.test> 1 +0000\n"
                + "committer Reader <reader@example.test> 1 +0000\n\n" + message + "\n";
        return repository.writeObject(GitObjectType.COMMIT, contents.getBytes(StandardCharsets.UTF_8));
    }

    private static void setHead(NativeGitRepository repository, Head head) throws IOException {
        repository.index().withAccess(index -> {
            index.updateHead(head);
            index.apply();
            return null;
        });
    }

    private static GitNativeRepositoryAccessHook visibilityHook(String branch) {
        List<GrantExpression> expressions = new ArrayList<>(List.of(
                new GrantExpression(GrantKey.REPOSITORY, "project"),
                new GrantExpression(GrantKey.READ_WRITE, "true")));
        if (branch != null) expressions.add(new GrantExpression(GrantKey.BRANCH, branch));
        return new AuthenticatedRepositoryAccessHook(SecurityContext.createContext().withUserIdentity(
                new InternalUserImpl("reader", List.of(new Grant("read", expressions)))));
    }

    private static String refAdvertisement(NativeGitRepositoryProvider provider,
            GitNativeRepositoryAccessHook hook, InitialRequestData request, boolean ssh, String... prefixes)
            throws Exception {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
        boolean protocolV2Upload = request.service() == InitialRequestService.UPLOAD_PACK
                && "2".equals(request.parameters().get("version"));
        if (!protocolV2Upload) {
            session(null, output, provider, hook).advertise(request);
        } else {
            try (QueueByteSource input = new QueueByteSource(Duration.ofSeconds(1))) {
                ByteArrayBuilder command = new ByteArrayBuilder();
                command.write(command("ls-refs"));
                for (String argument : List.of("symrefs", "peel", "unborn")) {
                    command.writePacket(argument + "\n");
                }
                for (String prefix : prefixes) {
                    command.writePacket(prefix + "\n");
                }
                command.writeAscii("0000");
                input.feed(command.bytes());
                input.end();
                if (ssh) session(input, output, provider, hook).serveCommand(request);
                else session(input, output, provider, hook).serveSmartHttpPost(request);
            }
        }
        return output.ascii();
    }

    @Test
    void receivePreservesOriginalPackThroughTheWireAndProvider() throws Exception {
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository = backend.create("project").valueOrFailure("repository");
        FileTestSupport.Prepared prepared = FileTestSupport.prepared(repository.files(), "main", "prepared",
                GitCommitAuthor.EMPTY, fileAccess -> {
            fileAccess.write("config.txt", new byte[]{1});
            return null;
        });
        byte[] original = prepared.pack();
        NativeGitRepositoryProvider provider = new NativeGitRepositoryProvider(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events())) {
            @Override
            public boolean isPublicRepositoryName(String name) {
                return backend.isPublicRepositoryName(name);
            }

            @Override
            public void close() {
                backend.close();
            }

            @Override
            public boolean exists(String name) {
                return backend.exists(name);
            }

            @Override
            public Result<NativeGitRepository> find(String name) {
                return backend.find(name);
            }

            @Override
            public Result<NativeGitRepository> create(String name) {
                return backend.create(name);
            }

            @Override
            public List<RefUpdateResult> publish(NativeGitRepository selected, Optional<PackChecksum> received,
                    List<RefUpdate> updates, boolean atomic) {
                try {
                    ByteArrayOutputStream exported = new ByteArrayOutputStream();
                    selected.index().withAccess(access1 -> {
                        selected.writePack(access1.packs(received.orElseThrow()).getFirst(),
                                new OutputStreamBufferedByteOutput(exported));
                        assertThat(exported.toByteArray()).isEqualTo(original);
                        return null;
                    });
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                return super.publish(selected, received, updates, atomic);
            }
        };
        try (QueueByteSource input = new QueueByteSource(Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(NULL_ID + " " + prepared.refUpdates().getFirst().newId().orElseThrow().toHex()
                    + " refs/heads/main\0report-status\n"));
            input.feed(original);

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii()).contains("ok refs/heads/main\n");
            assertThat(repository.refs()).containsEntry("refs/heads/main",
                    prepared.refUpdates().getFirst().newId().orElseThrow().toHex());
        }
    }

    @Test
    void advertiseWritesProtocolV2Capabilities() throws Exception {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
        session(null, output, providerWithMainRef()).advertise(uploadV2Request());

        assertThat(output.ascii())
                .startsWith("000eversion 2\n")
                .contains("fetch=")
                .contains("packfile-uris");
    }

    @Test
    void advertiseWritesProtocolV1MarkerForUploadPack() throws Exception {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();

        session(null, output, providerWithMainRef())
                .advertise(explicitUploadV1Request());

        assertThat(output.ascii())
                .startsWith("000eversion 1\n")
                .contains(MAIN_ID + " HEAD");
    }

    @Test
    void advertiseWritesProtocolV1MarkerForReceivePack() throws Exception {
        RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();

        session(null, output, providerWithMainRef())
                .advertise(explicitReceiveV1Request());

        assertThat(output.ascii())
                .startsWith("000eversion 1\n")
                .contains(MAIN_ID + " HEAD");
    }

    @Test
    void smartHttpPostReadsLsRefsRequestOneByteAtATime() throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Void> result = executor.submit(() -> {
                    session(input, output, providerWithMainRef())
                            .serveSmartHttpPost(uploadV2Request());
                    return null;
                });

                for (byte value : lsRefsRequest()) {
                    input.feed(new byte[] {value});
                }
                input.end();

                result.get(2, TimeUnit.SECONDS);
                assertThat(output.ascii())
                        .doesNotContain("version 2\n")
                        .contains(MAIN_ID + " HEAD symref-target:refs/heads/main")
                        .contains(MAIN_ID + " refs/heads/main");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void smartHttpPostFailsWhenLsRefsPayloadTimesOut() throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofMillis(25))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed("0012command=ls");

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Timed out");
        }
    }

    @Test
    void smartHttpPostWritesFetchNegotiationAcknowledgments()
            throws Exception {
        NativeGitRepositoryProvider provider =
                providerWithMainRef();
        NativeGitRepository repository =
                provider.find("project").valueOrFailure("repository");
        ObjectId have = repository.writeObject(
                GitObjectType.BLOB,
                "have".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + MAIN_ID + "\n",
                    "have " + have.toHex() + "\n",
                    "wait-for-done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0014acknowledgments\n"
                                    + "0031ACK " + have.toHex() + "\n"
                                    + "0000");
        }
    }

    @Test
    void smartHttpPostWritesFetchPackfileResponse() throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want " + blob.toHex() + "\n",
                    "thin-pack\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .startsWith("000dpackfile\n")
                    .contains("PACK");
        }
    }

    @Test
    void sshCommandServesLsRefsThenFetchOnSameProtocolV2Connection()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "initial"),
                        fileAccess -> {
                            fileAccess.write(
                                    "README.md", "payload".getBytes(StandardCharsets.US_ASCII));
                            fileAccess.apply();
                            return null;
                        });
        String mainId = repository.refs().get("refs/heads/main");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ByteArrayBuilder request = new ByteArrayBuilder();
            request.write(lsRefsRequest());
            request.write(fetchRequest(
                    "sideband-all\n",
                    "want-ref HEAD\n",
                    "want-ref refs/heads/main\n",
                    "done\n"));
            request.writeAscii("0000");
            input.feed(request.bytes());

            session(input, output, provider).serveCommand(uploadV2Request());

            assertThat(output.ascii())
                    .contains(mainId + " HEAD symref-target:refs/heads/main")
                    .contains(mainId + " refs/heads/main")
                    .contains("\u0001wanted-refs\n")
                    .contains(mainId + " HEAD\n")
                    .contains(mainId + " refs/heads/main\n")
                    .contains("\u0001packfile\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostWritesSidebandFetchPackfileResponseForWantedRefs()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/heads/main", NULL_ID, blob.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "sideband-all\n",
                    "want-ref HEAD\n",
                    "want-ref refs/heads/main\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii())
                    .contains("\u0001wanted-refs\n")
                    .contains(blob.toHex() + " HEAD\n")
                    .contains(blob.toHex() + " refs/heads/main\n")
                    .contains("\u0001packfile\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostAcceptsAdvertisedFetchServerOption() throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequestWithCapabilities(
                    List.of("server-option=trace\n"),
                    "want " + blob.toHex() + "\n",
                    "done\n"));
            input.end();

            session(input, output, provider).serveSmartHttpPost(uploadV2Request());

            assertThat(output.ascii()).startsWith("000dpackfile\n");
        }
    }

    @Test
    void smartHttpPostDeduplicatesFetchWantRefs() throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest(
                    "want-ref refs/heads/main\n",
                    "want-ref refs/heads/main\n",
                    "done\n"));

            session(input, output, providerWithMainRef()).serveSmartHttpPost(uploadV2Request());
            assertThat(output.ascii()).containsOnlyOnce(MAIN_ID + " refs/heads/main\n");

        }
    }

    @Test
    void smartHttpPostRejectsFetchWithoutWantsOrWantRefs()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(fetchRequest("have " + WANT + "\n", "done\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void smartHttpPostFailsWhenFetchPayloadTimesOut() throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofMillis(25))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(command("fetch"));
            input.feed("0012want " + WANT.substring(0, 8));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV2Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Timed out");
        }
    }

    @Test
    void smartHttpPostWritesLegacyUploadPackResponseOneByteAtATime()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId blob = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, blob.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            byte[] request = legacyUploadRequest(
                    "want " + blob.toHex() + " thin-pack ofs-delta\n",
                    "done\n");
            for (byte value : request) {
                input.feed(new byte[] {value});
            }

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii()).contains("PACK");
        }
    }

    @Test
    void smartHttpPostWritesLegacyMultiAckDetailedCommonAndFinalAckOnDone()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId want = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        ObjectId have = repository.writeObject(
                GitObjectType.BLOB,
                "base".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, want.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.toHex() + " multi_ack_detailed\n",
                    "have " + have.toHex() + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith(
                            "0038ACK " + have.toHex() + " common\n"
                                    + "0031ACK " + have.toHex() + "\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostEndsReadyNegotiationRoundWithNak() throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "base"),
                        fileAccess -> {
                            fileAccess.write(
                                    "README.md", "base".getBytes(StandardCharsets.US_ASCII));
                            fileAccess.apply();
                            return null;
                        });
        String have = repository.refs().get("refs/heads/main");
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "next"),
                        fileAccess -> {
                            fileAccess.write(
                                    "README.md", "next".getBytes(StandardCharsets.US_ASCII));
                            fileAccess.apply();
                            return null;
                        });
        String want = repository.refs().get("refs/heads/main");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRound(
                    List.of("want " + want + " multi_ack_detailed\n"),
                    "have " + have + "\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0038ACK " + have + " common\n"
                                    + "0037ACK " + have + " ready\n"
                                    + "0008NAK\n");
        }
    }

    @Test
    void smartHttpPostDoesNotSignalReadyUntilEveryWantReachesACommonHave()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "first"),
                        fileAccess -> {
                            fileAccess.write("first", new byte[] {1});
                            fileAccess.apply();
                            return null;
                        });
        repository
                .files()
                .withAccess(
                        "second",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "second"),
                        fileAccess -> {
                            fileAccess.write("second", new byte[] {2});
                            fileAccess.apply();
                            return null;
                        });
        ObjectId firstWant = new ObjectId(repository.refs().get("refs/heads/main"));
        ObjectId secondWant = new ObjectId(repository.refs().get("refs/heads/second"));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRound(
                    List.of(
                            "want " + firstWant.toHex()
                                    + " multi_ack_detailed\n",
                            "want " + secondWant.toHex() + "\n"),
                    "have " + firstWant.toHex() + "\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "0038ACK " + firstWant.toHex() + " common\n"
                                    + "0008NAK\n");
        }
    }

    @Test
    void commandPreservesLegacyNegotiationAcrossFlushDelimitedRounds()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "base"),
                        fileAccess -> {
                            fileAccess.write(
                                    "README.md", "base".getBytes(StandardCharsets.US_ASCII));
                            fileAccess.apply();
                            return null;
                        });
        String have = repository.refs().get("refs/heads/main");
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "next"),
                        fileAccess -> {
                            fileAccess.write(
                                    "README.md", "next".getBytes(StandardCharsets.US_ASCII));
                            fileAccess.apply();
                            return null;
                        });
        String want = repository.refs().get("refs/heads/main");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRounds(
                    "want " + want + " multi_ack_detailed\n",
                    List.of("have " + WANT + "\n"),
                    List.of("have " + have + "\n"),
                    "done\n"));

            session(input, output, provider).serveCommand(uploadV1Request());

            assertThat(output.ascii())
                    .contains(
                            "0008NAK\n"
                                    + "0038ACK " + have + " common\n"
                                    + "0037ACK " + have + " ready\n"
                                    + "0008NAK\n"
                                    + "0031ACK " + have + "\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostWritesLegacyMultiAckDetailedNakWhenNoHaveIsCommon()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId want = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, want.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.toHex() + " multi_ack_detailed\n",
                    "have " + WANT + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith("0008NAK\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostWritesLegacyMultiAckContinueForCommonHave()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        ObjectId want = repository.writeObject(
                GitObjectType.BLOB,
                "payload".getBytes(StandardCharsets.US_ASCII));
        ObjectId have = repository.writeObject(
                GitObjectType.BLOB,
                "base".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/heads/main", NULL_ID, want.toHex());
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + want.toHex() + " multi_ack\n",
                    "have " + have.toHex() + "\n",
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith(
                            "003aACK " + have.toHex() + " continue\n"
                                    + "0031ACK " + have.toHex() + "\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostRejectsLegacyUploadInvalidObjectId()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest("want invalid\n", "done\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(uploadV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Invalid fetch object ID");
        }
    }

    @Test
    void smartHttpPostRejectsUnadvertisedLegacyUploadWant()
            throws Exception {
        NativeGitRepositoryProvider provider = providerWithMainRef();
        NativeGitRepository repository =
                provider.find("project").valueOrFailure("repository");
        ObjectId hidden = repository.writeObject(
                GitObjectType.BLOB,
                "hidden".getBytes(StandardCharsets.US_ASCII));
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRequest(
                    "want " + hidden.toHex() + "\n",
                    "done\n"));

            assertThatThrownBy(() -> session(input, output, provider)
                    .serveSmartHttpPost(uploadV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Want is not an advertised object");
        }
    }

    @Test
    void smartHttpPostWritesLegacyShallowBoundaryBeforeNegotiation()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository =
                provider.create("project").valueOrFailure("repository");
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "root"),
                        fileAccess -> {
                            fileAccess.write(
                                    "README.md", "root".getBytes(StandardCharsets.US_ASCII));
                            fileAccess.apply();
                            return null;
                        });
        repository
                .files()
                .withAccess(
                        "main",
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "tip"),
                        fileAccess -> {
                            fileAccess.write(
                                    "README.md", "tip".getBytes(StandardCharsets.US_ASCII));
                            fileAccess.apply();
                            return null;
                        });
        String tip = repository.refs().get("refs/heads/main");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyUploadRound(
                    List.of(
                            "want " + tip + " shallow\n",
                            "deepen 1\n"),
                    "done\n"));

            session(input, output, provider).serveSmartHttpPost(uploadV1Request());

            assertThat(output.ascii())
                    .startsWith("0035shallow " + tip + "\n0000")
                    .contains("NAK\n")
                    .contains("PACK");
        }
    }

    @Test
    void smartHttpPostRejectsContradictoryLegacyDeepeningForms()
            throws Exception {
        for (List<String> requestLines : List.of(
                List.of(
                        "want " + MAIN_ID + " shallow\n",
                        "deepen 1\n",
                        "deepen-since 1\n"),
                List.of(
                        "want " + MAIN_ID + " shallow\n",
                        "deepen-relative\n"))) {
            try (QueueByteSource input = new QueueByteSource(
                    Duration.ofSeconds(1))) {
                RecordingBufferedByteOutput output =
                        new RecordingBufferedByteOutput();
                input.feed(legacyUploadRound(requestLines, "done\n"));

                assertThatThrownBy(() -> session(
                        input,
                        output,
                        providerWithMainRef())
                        .serveSmartHttpPost(uploadV1Request()))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void smartHttpPostWritesLegacyReceivePackStatusForDelete()
            throws Exception {
        NativeGitRepositoryProvider provider = providerWithMainRef();
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "000eunpack ok\n"
                                    + "0017ok refs/heads/main\n"
                                    + "0000");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey("refs/heads/main");
        }
    }

    @Test
    void smartHttpPostAcceptsReceiveShallowPrefixesBeforeCommands()
            throws Exception {
        NativeGitRepositoryProvider provider = providerWithMainRef();
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "shallow " + "a".repeat(40) + "\n",
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("unpack ok\n")
                    .contains("ok refs/heads/main\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey("refs/heads/main");
        }
    }

    @Test
    void smartHttpPostRejectsReceiveShallowPrefixAfterCommand()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n",
                    "shallow " + "a".repeat(40) + "\n"));

            assertThatThrownBy(() -> session(
                    input,
                    output,
                    providerWithMainRef()).serveSmartHttpPost(receiveV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Shallow declarations must precede push commands");
        }
    }

    @Test
    void receivePackAppliesValidCommandsAfterNonAtomicStaleCommand()
            throws Exception {
        NativeGitRepositoryProvider provider = providerWithMainRef();
        provider.find("project").valueOrFailure("repository")
                .updateRef("refs/heads/feature", NULL_ID, MAIN_ID);
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "3".repeat(40)
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n",
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/feature\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("ng refs/heads/main stale info\n")
                    .contains("ok refs/heads/feature\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .containsEntry("refs/heads/main", MAIN_ID)
                    .doesNotContainKey("refs/heads/feature");
        }
    }

    @Test
    void receivePackRollsBackValidCommandsWhenAtomicCommandIsStale()
            throws Exception {
        NativeGitRepositoryProvider provider = providerWithMainRef();
        provider.find("project").valueOrFailure("repository")
                .updateRef("refs/heads/feature", NULL_ID, MAIN_ID);
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "3".repeat(40)
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status atomic\n",
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/feature\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("ng refs/heads/main stale info\n")
                    .contains(
                            "ng refs/heads/feature atomic push failure\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .containsEntry("refs/heads/main", MAIN_ID)
                    .containsEntry("refs/heads/feature", MAIN_ID);
        }
    }

    @Test
    void smartHttpReceivePackV2OfferFallsBackToLegacyProtocol()
            throws Exception {
        NativeGitRepositoryProvider provider = providerWithMainRef();
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID
                            + " "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV2Request());

            assertThat(output.ascii())
                    .isEqualTo(
                            "000eunpack ok\n"
                                    + "0017ok refs/heads/main\n"
                                    + "0000");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey("refs/heads/main");
        }
    }

    @Test
    void smartHttpPostReportsMalformedReceivePackThroughStatusV2()
            throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        provider.create("project").valueOrFailure("repository");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            ByteArrayBuilder request = new ByteArrayBuilder();
            request.writePacket(
                    NULL_ID
                            + " "
                            + WANT
                            + " refs/heads/main\0report-status-v2\n");
            request.writeAscii("0000BAD");
            input.feed(request.bytes());
            input.end();

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii())
                    .contains("unpack unpacker error\n")
                    .contains("ng refs/heads/main unpacker error\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey("refs/heads/main");
        }
    }

    @Test
    void smartHttpPostAcceptsEmptyReceiveCommandSection() throws Exception {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        provider.create("project").valueOrFailure("repository");
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed("0000");

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(output.ascii()).isEmpty();
        }
    }

    @Test
    void smartHttpPostAcceptsNonAsciiLegacyReceiveRefName() throws Exception {
        String refName = "refs/heads/feature-фи";
        NativeGitRepositoryProvider provider = providerWithMainRef();
        provider.find("project").valueOrFailure("repository")
                .updateRef(refName, NULL_ID, MAIN_ID);
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    MAIN_ID + " " + NULL_ID + " " + refName
                            + "\0report-status\n"));

            session(input, output, provider).serveSmartHttpPost(receiveV1Request());

            assertThat(new String(output.bytes(), StandardCharsets.UTF_8))
                    .contains("ok " + refName + "\n");
            assertThat(provider.find("project")
                    .valueOrFailure("repository")
                    .refs())
                    .doesNotContainKey(refName);
        }
    }

    @Test
    void smartHttpPostRejectsLegacyReceiveRefNameWithForbiddenGitCharacters()
            throws Exception {
        for (String character : List.of("~", "^", ":", "?", "*", "[", "\\")) {
            try (QueueByteSource input = new QueueByteSource(
                        Duration.ofSeconds(1))) {
                RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
                input.feed(legacyReceiveRequest(
                        MAIN_ID
                                + " "
                                + NULL_ID
                                + " refs/heads/feature"
                                + character
                                + "x\0report-status\n"));

                assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                        .serveSmartHttpPost(receiveV1Request()))
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("Invalid push command");
            }
        }
    }

    @Test
    void smartHttpPostRejectsLegacyReceiveInvalidObjectId()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofSeconds(1))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    "invalid "
                            + NULL_ID
                            + " refs/heads/main\0report-status\n"));

            assertThatThrownBy(() -> session(input, output, providerWithMainRef())
                    .serveSmartHttpPost(receiveV1Request()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(
                            "Invalid push command");
        }
    }

    @Test
    void smartHttpPostReportsFailureWhenReceivePackBodyTimesOut()
            throws Exception {
        try (QueueByteSource input = new QueueByteSource(
                Duration.ofMillis(25))) {
            RecordingBufferedByteOutput output = new RecordingBufferedByteOutput();
            input.feed(legacyReceiveRequest(
                    NULL_ID
                            + " "
                            + WANT
                            + " refs/heads/new\0report-status\n"));

            NativeGitRepositoryProvider provider = providerWithMainRef();
            session(input, output, provider).serveSmartHttpPost(receiveV1Request());
            assertThat(output.ascii()).contains("unpack unpacker error\n", "ng refs/heads/new unpacker error\n");
            assertThat(provider.find("project").valueOrFailure("repository").refs())
                    .doesNotContainKey("refs/heads/new");

        }
    }

    private static GitBlockingWireSession session(
            QueueByteSource input,
            RecordingBufferedByteOutput output,
            NativeGitRepositoryProvider provider) {
        return session(input, output, provider, GitNativeRepositoryAccessHook.ALLOW_ALL);
    }

    private static GitBlockingWireSession session(QueueByteSource input, RecordingBufferedByteOutput output,
            NativeGitRepositoryProvider provider, GitNativeRepositoryAccessHook hook) {
        GitBlockingWireTransport wire = input == null
                ? new GitBlockingWireTransport(output)
                : new GitBlockingWireTransport(new BufferedByteInputV2(input), output);
        return new GitBlockingWireSession(
                data -> new DefaultGitNativeRepositoryService(provider).open(
                        data, hook),
                GitWireConfiguration.allSupported(),
                wire);
    }

    private NativeGitRepositoryProvider providerWithMainRef() {
        NativeGitRepositoryProvider provider =
                NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository = provider.create("project").valueOrFailure("repository");
        ObjectId id = repository.writeObject(GitObjectType.BLOB, "main".getBytes(StandardCharsets.US_ASCII));
        assertThat(id.toHex()).isEqualTo(MAIN_ID);
        repository.updateRef("refs/heads/main", "0".repeat(40), id.toHex());
        return provider;
    }

    private static InitialRequestData uploadV2Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of("version", "2"));
    }

    private static InitialRequestData uploadV1Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of());
    }

    private static InitialRequestData explicitUploadV1Request() {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                "project",
                "git.example",
                Map.of("version", "1"));
    }

    private static InitialRequestData receiveV1Request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "project",
                "git.example",
                Map.of());
    }

    private static InitialRequestData explicitReceiveV1Request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "project",
                "git.example",
                Map.of("version", "1"));
    }

    private static InitialRequestData receiveV2Request() {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                "project",
                "git.example",
                Map.of("version", "2"));
    }

    private static byte[] lsRefsRequest() {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.write(command("ls-refs"));
        output.writePacket("symrefs\n");
        output.writePacket("ref-prefix HEAD\n");
        output.writePacket("ref-prefix refs/heads/\n");
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] fetchRequest(String... arguments) {
        return fetchRequestWithCapabilities(List.of(), arguments);
    }

    private static byte[] fetchRequestWithCapabilities(
            List<String> capabilities,
            String... arguments) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket("command=fetch\n");
        for (String capability : capabilities) {
            output.writePacket(capability);
        }
        output.writeAscii("0001");
        for (String argument : arguments) {
            output.writePacket(argument);
        }
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] legacyUploadRequest(String... lines) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket(lines[0]);
        output.writeAscii("0000");
        for (int index = 1; index < lines.length; index++) {
            output.writePacket(lines[index]);
        }
        return output.bytes();
    }

    private static byte[] legacyUploadRound(
            List<String> wants,
            String... haves) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        for (String want : wants) {
            output.writePacket(want);
        }
        output.writeAscii("0000");
        for (String have : haves) {
            output.writePacket(have);
        }
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] legacyUploadRounds(
            String want,
            List<String> firstRound,
            List<String> secondRound,
            String done) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket(want);
        output.writeAscii("0000");
        for (String have : firstRound) {
            output.writePacket(have);
        }
        output.writeAscii("0000");
        for (String have : secondRound) {
            output.writePacket(have);
        }
        output.writeAscii("0000");
        output.writePacket(done);
        return output.bytes();
    }

    private static byte[] legacyReceiveRequest(String... lines) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        for (String line : lines) {
            output.writePacket(line);
        }
        output.writeAscii("0000");
        return output.bytes();
    }

    private static byte[] command(String command) {
        ByteArrayBuilder output = new ByteArrayBuilder();
        output.writePacket("command=" + command + "\n");
        output.writeAscii("0001");
        return output.bytes();
    }

    private static final class ByteArrayBuilder {
        private byte[] bytes = new byte[128];
        private int size;

        void writePacket(String payload) {
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            writeAscii("%04x".formatted(payloadBytes.length + 4));
            write(payloadBytes);
        }

        void writeAscii(String value) {
            write(value.getBytes(StandardCharsets.US_ASCII));
        }

        byte[] bytes() {
            byte[] copy = new byte[size];
            System.arraycopy(bytes, 0, copy, 0, size);
            return copy;
        }

        private void write(byte[] source) {
            if (size + source.length > bytes.length) {
                byte[] next = new byte[Math.max(
                        bytes.length * 2,
                        size + source.length)];
                System.arraycopy(bytes, 0, next, 0, size);
                bytes = next;
            }
            System.arraycopy(source, 0, bytes, size, source.length);
            size += source.length;
        }
    }
}
