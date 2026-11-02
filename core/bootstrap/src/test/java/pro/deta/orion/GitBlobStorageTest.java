package pro.deta.orion;

import pro.deta.orion.keymaterial.KeyMaterialOperation;

import pro.deta.orion.git.fileapi.GitBlobStorage;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.util.VcResourceModified;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.storage.BlobStorage.BlobStorageOperations;
import pro.deta.orion.git.fileapi.GitFileAccess;
import pro.deta.orion.event.OrionEvents;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.util.VcModificationException;

import java.math.BigInteger;
import java.util.Date;
import java.time.Instant;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.acme.AcmeCertificateService;
import pro.deta.orion.config.BlobOrionConfigurationStorage;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.acme.AcmeCertificateIssuer;
import pro.deta.orion.acme.AcmeCertificateIssueRequest;
import pro.deta.orion.acme.IssuedAcmeCertificate;
import pro.deta.orion.acme.AcmeHttpChallengeService;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrionXml;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Optional;

import pro.deta.orion.util.VcChangeAuthor;

import pro.deta.orion.util.VcChangeDescription;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.fileapi.GitFileEvents;
import pro.deta.orion.git.fileapi.GitFileChanged;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import pro.deta.orion.storage.BlobStorageConflictException;
import pro.deta.orion.bootstrap.config.BootstrapSourceConfig;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitBlobStorageTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime testRuntime =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private static final String REPOSITORY = "orion";
    private static final String REF = "refs/heads/main";
    private static final String MATERIAL_PATH = "material.p12";

    @TempDir
    private Path tempDir;

    @Test
    void subscribesOnlyToItsBlobAndStopsDeliveryAfterUnsubscribe() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("<orion/>"), MATERIAL_PATH, bytes("material")));
        try (NativeGitRepositoryProvider provider = fixture.provider();
             GitBlobStorage configuration = new GitBlobStorage(
                     new GitFileEvents(new GitFile(provider, REPOSITORY, REF, "orion.xml")));
             GitBlobStorage material = fixture.store()) {
            List<VcResourceModified> changes = new CopyOnWriteArrayList<>();
            OrionEvents subscription = testRuntime.events().forTarget(new Object());
            subscription.subscribe(VcResourceModified.class, configuration, changes::add);
            VcRevision.Stored previous = open(configuration).revision();
            VcRevision.Stored beforeConfiguration = save(material, open(material).revision(), bytes("other material"));
            testRuntime.awaitEvents();
            assertThat(changes).isEmpty();
            VcRevision.Stored saved = save(configuration, previous, bytes("<orion />"));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(VcResourceModified::revision).containsExactly(saved);
            assertThat(changes.getFirst().oldRevision()).isEqualTo(beforeConfiguration);
            VcRevision.Stored next = save(configuration, saved, bytes("<next/>"));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(VcResourceModified::revision).containsExactly(saved, next);
            assertThat(changes.getLast().oldRevision()).isEqualTo(saved);
            configuration.readBytes(next);
            VcRevision.Stored accepted = save(configuration, next, bytes("<accepted/>"));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(VcResourceModified::revision).containsExactly(saved, next, accepted);
            assertThat(changes.getLast().oldRevision()).isEqualTo(next);
            save(material, open(material).revision(), bytes("updated material"));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(VcResourceModified::revision).containsExactly(saved, next, accepted);
            subscription.unsubscribe(configuration);
            save(configuration, open(configuration).revision(),
                    bytes("<closed/>"));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(VcResourceModified::revision).containsExactly(saved, next, accepted);
        }
    }

    @Test
    void reportsNoOldRevisionWhenCreatingANewBranch() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("<orion/>")));
        try (NativeGitRepositoryProvider provider = fixture.provider();
             GitBlobStorage storage = new GitBlobStorage(new GitFileEvents(
                     new GitFile(provider, REPOSITORY, "refs/heads/new", "orion.xml")))) {
            List<VcResourceModified> changes = new CopyOnWriteArrayList<>();
            OrionEvents subscription = testRuntime.events().forTarget(new Object());
            subscription.subscribe(VcResourceModified.class, storage, changes::add);
            try {
                VcRevision.Stored saved = new VcRevision.Stored(fixture.repository().refs().get(REF));
                fixture.repository().updateRef("refs/heads/new", "0".repeat(40), saved.value());
                testRuntime.awaitEvents();
                assertThat(changes).extracting(VcResourceModified::revision).containsExactly(saved);
                assertThat(changes.getFirst().oldRevision()).isEqualTo(new VcRevision.Absent());
            } finally {
                subscription.unsubscribe(storage);
            }
        }
    }

    @Test
    void storesOpaqueXmlWithCallerCommitMetadata() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("<orion/>")));
        VcChangeDescription metadata = new VcChangeDescription(
                new VcChangeAuthor("Configuration editor", "editor@example.test"), "Update configuration");
        try (NativeGitRepositoryProvider provider = fixture.provider();
             GitBlobStorage storage = new GitBlobStorage(
                     new GitFileEvents(new GitFile(provider, REPOSITORY, REF, "orion.xml")))) {
            BlobStorageOperations initial = open(storage);
            VcRevision.Stored saved = save(storage, initial.revision(), bytes("<orion />"), metadata);
            assertThat(storage.readBytes(saved)).isEqualTo(bytes("<orion />"));
            assertThat(initial.readContent()).isEqualTo(bytes("<orion/>"));
            byte[] commit = fixture.repository().readObject(
                    new ObjectId(saved.value())).orElseThrow().data();
            assertThat(new String(commit, StandardCharsets.UTF_8))
                    .contains("author Configuration editor <editor@example.test>")
                    .endsWith("Update configuration\n");
        }
    }

    @Test
    void activatesAnIssuedCertificateWhenConfigurationAndMaterialShareTheGitBranch() throws Exception {
        KeyMaterialId account = new KeyMaterialId("account", 1);
        KeyMaterialId identity = new KeyMaterialId("identity", 1);
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(true,
                URI.create("acme://letsencrypt.org/staging"),
                "admin@example.test", List.of("example.test"), "ORION", Optional.of(account),
                30, 40, true, false, Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 8443, null,
                Optional.of(identity), Optional.empty(),
                        OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                List.of(), Optional.of(acme));
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.of(https), List.of(), List.of(), List.of(), List.of(), List.of()), List.of());
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        OrionXml.write(document, xml);
        Fixture fixture = fixture(Map.of("orion.xml", xml.toByteArray()));
        try (NativeGitRepositoryProvider provider = fixture.provider();
             KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray());
             OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(fixture.store(),
                     new KeyMaterialCodec(options), testRuntime.events()));
             BlobOrionConfigurationStorage storage = new BlobOrionConfigurationStorage(new GitBlobStorage(new GitFileEvents(
                     new GitFile(provider, REPOSITORY, REF, "orion.xml"))), testRuntime.unstartedEvents())) {
            try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                modification.generateKey("account", Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 2048);
                modification.generateKey("identity", Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            OrionConfigurationEditor editor = new OrionConfigurationEditor(storage, material,
                    testRuntime.events());
            editor.onStart();
            try {
                AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(new AcmeHttpChallengeService()) {
                    @Override
                    public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
                        try {
                            X500Principal subject =
                                    new X500Principal("CN=example.test");
                            Instant now = Instant.now();
                            X509v3CertificateBuilder builder =
                                    new JcaX509v3CertificateBuilder(subject,
                                            BigInteger.ONE, Date.from(now.minusSeconds(60)),
                                            Date.from(now.plusSeconds(3600)), subject,
                                            request.domainKeyPair().getPublic());
                            X509Certificate certificate =
                                    new JcaX509CertificateConverter().getCertificate(
                                            builder.build(new JcaContentSignerBuilder(
                                                    "SHA256withRSA").build(request.domainKeyPair().getPrivate())));
                            return new IssuedAcmeCertificate(request.domains(), List.of(certificate));
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                };
                AcmeCertificateService service = new AcmeCertificateService(editor, material, issuer,
                        null, testRuntime.executor());
                IssuedAcmeCertificate issued = service.issue(AcmeCertificateService.IssueRequest.EMPTY);
                testRuntime.awaitEvents();
                KeyMaterialId active = editor.current().result().valueOrFailure("configuration")
                        .system().https().orElseThrow().identity().orElseThrow();
                assertThat(active).isEqualTo(new KeyMaterialId("identity", 2));
                assertThat(material.read(active).certificateChain())
                        .containsExactlyElementsOf(issued.certificateChain());
                assertThat(storage.load().result().valueOrFailure("stored configuration").system().https()
                        .orElseThrow().identity()).contains(active);
            } finally {
                editor.onStop();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("Publication under commit lock: docs/plans/tasks/22_vc-event-publication.md")
    void allowsReadingMaterialWhileARefChangeIsBeingPublished() throws Exception {
        java.util.concurrent.atomic.AtomicReference<GitBlobStorage> activeStore =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            pro.deta.orion.event.OrionEventManager events = new pro.deta.orion.event.OrionEventManager() {
                @Override
                public void publish(pro.deta.orion.event.type.OrionEvent event) {
                    if (event instanceof pro.deta.orion.git.nativestorage.RepositoryRefChanged
                            && activeStore.get() != null) {
                        try {
                            executor.submit(() -> {
                                try (BlobStorageOperations operation = activeStore.get().open(new VcRevision.Latest())) {
                                    return operation.revision();
                                }
                            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new AssertionError("Store read is blocked by ref event publication", failure);
                        }
                    }
                    super.publish(event);
                }
            };
            events.onStart();
            try (NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(events)) {
                seedEmptyCommit(provider.create(REPOSITORY).valueOrFailure("create repository"));
                try (GitBlobStorage store =
                        new GitBlobStorage(new GitFileEvents(
                                new GitFile(
                                    provider,
                                    REPOSITORY,
                                    REF,
                                    MATERIAL_PATH)))) {
                    activeStore.set(store);
                    VcRevision.Stored saved = save(store, open(store).revision(), bytes("material"));
                    assertThat(open(store).revision()).isEqualTo(saved);
                }
            } finally {
                events.onStop();
            }
        }
    }

    @Test
    void opensEmptyMaterialOnAnExistingBranchAndPublishesAgainstItsRevision() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("configuration")));
        GitBlobStorage source = fixture.store();
        VcRevision.Stored base = new VcRevision.Stored(fixture.repository().refs().get(REF));
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray());
             OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                     source, new KeyMaterialCodec(options), testRuntime.events()))) {
            assertThat(material.revision()).isEqualTo(base.value());
            assertThat(material.hasDurableSnapshot()).isFalse();
            KeyMaterialId signing = new KeyMaterialId("signing", 1);
            pro.deta.orion.keymaterial.KeyMaterialOperation modification =
                    material.open(new VcRevision.Stored(material.revision()));
            assertThat(modification.revision().value()).isEqualTo(base.value());
            modification.generateKey("signing", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
            modification.commit(VcChangeDescription.EMPTY);
            assertThat(material.revision()).isEqualTo(open(source).revision().value()).isNotEqualTo(base.value());
            assertThat(material.hasDurableSnapshot()).isTrue();
            assertThat(material.contains(signing)).isTrue();
            assertThat(readGit(fixture.repository(), "orion.xml"))
                    .isEqualTo(bytes("configuration"));
        }
    }

    @Test
    void createsMissingMaterialOnExistingBranchWithoutReplacingConfiguration() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("configuration")));
        GitBlobStorage store = fixture.store();

        VcRevision.Stored base = new VcRevision.Stored(fixture.repository().refs().get(REF));
        BlobStorageOperations initial = open(store);
        assertThat(initial.revision()).isEqualTo(base);
        assertThatThrownBy(initial::readContent).isInstanceOf(java.io.UncheckedIOException.class)
                .hasCauseInstanceOf(java.io.FileNotFoundException.class);
        assertThatThrownBy(() -> store.readBytes(base)).isInstanceOf(java.io.UncheckedIOException.class)
                .hasCauseInstanceOf(java.io.FileNotFoundException.class);
        VcRevision.Stored version = save(store, base, bytes("encrypted-material"));

        assertThat(open(store).revision()).isEqualTo(version);
        assertThat(readGit(fixture.repository(), "orion.xml")).isEqualTo(bytes("configuration"));
        assertThat(readGit(fixture.repository(), MATERIAL_PATH))
                .isEqualTo(bytes("encrypted-material"));
        assertThatThrownBy(() -> save(store, base, bytes("unexpected replacement")))
                .isInstanceOf(VcModificationException.class);
    }

    @Test
    void preservesUnrelatedConfigurationChangesWithoutReloadingMaterial() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial"),
                "orion.xml", bytes("initial config")));
        GitBlobStorage store = fixture.store();
        BlobStorageOperations initial = open(store);
        VcRevision.Stored version = initial.revision();
        {
            NativeGitRepository targetRepository = fixture.repository();
            VcRevision.Ref branch = targetRepository.ref(REF);
            try (GitFileAccess fileAccess = targetRepository.files().open(branch)) {
                fileAccess.write("orion.xml", bytes("new config"));
                VcRevision.Stored created =
                        fileAccess.commit(
                                new VcChangeDescription(
                                        VcChangeAuthor.EMPTY, "configuration update"));
                targetRepository.refUpdate(branch, fileAccess.revision(), created);
            }
        }

        assertThat(initial.readContent()).isEqualTo(bytes("initial"));
        assertThat(open(store).revision()).isNotEqualTo(version);
        VcRevision.Stored saved = save(store, version, bytes("updated material"));

        assertThat(open(store).revision()).isEqualTo(saved);
        assertThat(readGit(fixture.repository(), "orion.xml")).isEqualTo(bytes("new config"));
        assertThat(readGit(fixture.repository(), MATERIAL_PATH))
                .isEqualTo(bytes("updated material"));
    }

    @Test
    void rejectsAStaleFileRevisionAtPublication() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial")));
        GitBlobStorage first = fixture.store();
        GitBlobStorage second = fixture.store();
        VcRevision.Stored firstRevision = open(first).revision();

        VcRevision.Stored secondRevision = open(second).revision();

        save(first, firstRevision, bytes("first"));

        assertThatThrownBy(() -> save(second, secondRevision, bytes("second")))
                .isInstanceOf(VcModificationException.class);
        assertThat(open(first).readContent()).isEqualTo(bytes("first"));
    }

    @Test
    void rejectsAnUpdateCommittedBetweenPreparationAndPublication() throws Exception {
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository = backend.create(REPOSITORY).valueOrFailure("create repository");
        {
            NativeGitRepository targetRepository = repository;
            seedEmptyCommit(targetRepository);
            VcRevision.Ref branch = targetRepository.ref(REF);
            try (GitFileAccess fileAccess = targetRepository.files().open(branch)) {
                fileAccess.write(MATERIAL_PATH, bytes("initial"));
                VcRevision.Stored created =
                        fileAccess.commit(
                                new VcChangeDescription(VcChangeAuthor.EMPTY, "seed repository"));
                targetRepository.refUpdate(branch, fileAccess.revision(), created);
            }
        }
        InterleavingProvider provider =
                new InterleavingProvider(
                        backend,
                        () -> {
                            try {
                                NativeGitRepository targetRepository =
                                        backend.openForWrite(REPOSITORY)
                                                .valueOrFailure("repository");
                                VcRevision.Ref branch = targetRepository.ref(REF);
                                try (GitFileAccess fileAccess =
                                        targetRepository.files().open(branch)) {
                                    fileAccess.write(
                                            "orion.xml", bytes("concurrent configuration"));
                                    fileAccess.write(MATERIAL_PATH, bytes("concurrent material"));
                                    VcRevision.Stored created =
                                            fileAccess.commit(
                                                    new VcChangeDescription(
                                                            VcChangeAuthor.EMPTY,
                                                            "concurrent update"));
                                    targetRepository.refUpdate(
                                            branch, fileAccess.revision(), created);
                                }
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        GitBlobStorage store = new GitBlobStorage(new GitFileEvents(
                new GitFile(
                    provider,
                    REPOSITORY,
                    REF,
                    MATERIAL_PATH)));
        VcRevision.Stored observedVersion = open(store).revision();

        assertThatThrownBy(() -> save(store, observedVersion,
                bytes("stale material")))
                .isInstanceOf(VcModificationException.class);

        assertThat(readGit(repository, MATERIAL_PATH)).isEqualTo(bytes("concurrent material"));
        assertThat(readGit(repository, "orion.xml")).isEqualTo(bytes("concurrent configuration"));
    }

    @Test
    void publishesMissingMaterialThroughRemoteProviderAndPreservesConfiguration() throws Exception {
        Path worktree = tempDir.resolve("worktree");
        Path bare = tempDir.resolve("upstream.git");
        Git git = Git.init().setDirectory(worktree.toFile()).setInitialBranch("main").call();
        Files.write(worktree.resolve("orion.xml"), bytes("configuration"));
        git.add().addFilepattern("orion.xml").call();
        git.commit().setMessage("configuration").setAuthor("Test", "test@example.invalid").call();
        try (Git ignored = Git.cloneRepository()
                .setURI(worktree.toUri().toString())
                .setDirectory(bare.toFile())
                .setBare(true)
                .call()) {
            // Bare fixture is ready.
        }

        NativeGitRepositoryFactory factory = new NativeGitRepositoryFactory(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events()));
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation("git+" + bare.toUri());
        source.setRef(REF);
        source.setPath(MATERIAL_PATH);
        String repositoryName = factory.resolveProvisional("material", source, true).orElseThrow();
        GitBlobStorage store = new GitBlobStorage(new GitFileEvents(
                new GitFile(
                    factory.provider(),
                    repositoryName,
                    NativeGitRepositoryFactory.sourceRefName(source),
                    NativeGitRepositoryFactory.repositoryPath(source.getPath()))));

        VcRevision.Stored base = open(store).revision();
        assertThatThrownBy(() -> store.readBytes(base)).isInstanceOf(java.io.UncheckedIOException.class)
                .hasCauseInstanceOf(java.io.FileNotFoundException.class);
        save(store, base, bytes("encrypted-material"));

        Path checkout = tempDir.resolve("checkout");
        try (Git ignored = Git.cloneRepository()
                .setURI(bare.toUri().toString())
                .setDirectory(checkout.toFile())
                .call()) {
            assertThat(Files.readAllBytes(checkout.resolve("orion.xml"))).isEqualTo(bytes("configuration"));
            assertThat(Files.readAllBytes(checkout.resolve(MATERIAL_PATH)))
                    .isEqualTo(bytes("encrypted-material"));
        } finally {
            git.close();
        }
    }

    @Test
    void reportsOnlyFileChangesAndReadsTheirExactCommits() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial")));
        try (GitBlobStorage store = fixture.store()) {
            testRuntime.awaitEvents();
            List<VcRevision.Stored> revisions = new CopyOnWriteArrayList<>();
            GitFile file = new GitFile(fixture.provider(), REPOSITORY, REF, MATERIAL_PATH);
            GitFileEvents source = new GitFileEvents(file);
            testRuntime.own(source);
            OrionEvents subscription = source.events().forTarget(revisions);
            subscription.subscribe(GitFileChanged.class, source.source(),
                    event -> revisions.add(new VcRevision.Stored(event.revision().toHex())));
            testRuntime.own(() -> subscription.unsubscribe(source.source()));
            {
                NativeGitRepository targetRepository = fixture.repository();
                VcRevision.Ref branch = targetRepository.ref(REF);
                try (GitFileAccess access = targetRepository.files().open(branch)) {
                    access.write("orion.xml", bytes("updated configuration"));
                    VcRevision.Stored created =
                            access.commit(
                                    new VcChangeDescription(
                                            VcChangeAuthor.EMPTY, "unrelated file"));
                    targetRepository.refUpdate(branch, access.revision(), created);
                }
            }
            {
                NativeGitRepository targetRepository = fixture.repository();
                targetRepository.updateRef("refs/heads/other", "0".repeat(40), targetRepository.refs().get(REF));
                VcRevision.Ref branch = targetRepository.ref("refs/heads/other");
                try (GitFileAccess access = targetRepository.files().open(branch)) {
                    access.write(MATERIAL_PATH, bytes("other branch"));
                    VcRevision.Stored created =
                            access.commit(
                                    new VcChangeDescription(VcChangeAuthor.EMPTY, "other branch"));
                    targetRepository.refUpdate(branch, access.revision(), created);
                }
            }
            testRuntime.awaitEvents();
            assertThat(revisions).isEmpty();
            VcRevision.Stored first = save(store, open(store).revision(), bytes("first"));
            VcRevision.Stored second = save(store, first, bytes("second"));
            testRuntime.awaitEvents();
            assertThat(revisions).containsExactly(first, second);
            assertThat(store.readBytes(first)).isEqualTo(bytes("first"));
            assertThat(store.readBytes(second)).isEqualTo(bytes("second"));
        }
    }

    @Test
    void retainsRunningMaterialWhenAnExternalUpdateReplacesItsHistory() throws Exception {
        char[] password = "test-password".toCharArray();
        InMemoryBlobStorage external = emptyMaterialStore();
        KeyMaterialId original = new KeyMaterialId("original", 1);
        KeyMaterialId local = new KeyMaterialId("local", 1);
        KeyMaterialId added = new KeyMaterialId("added", 1);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(password);
             OrionKeyMaterial writer = OrionKeyMaterial.open(new KeyMaterialRepository(
                     external,
                     new KeyMaterialCodec(options),
                     testRuntime.unstartedEvents()))) {
            try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                modification.generateKey("original", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            Fixture fixture = fixture(Map.of(MATERIAL_PATH, open(external).readContent()));
            try (OrionKeyMaterial running = OrionKeyMaterial.open(new KeyMaterialRepository(
                    fixture.store(),
                    new KeyMaterialCodec(options),
                    testRuntime.events()))) {
                try (KeyMaterialOperation modification = running.open(new VcRevision.Stored(running.revision()))) {
                    modification.generateKey("local", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                testRuntime.awaitEvents();
                String localRevision = running.revision();
                try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                    modification.generateKey("added", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                {
                    NativeGitRepository targetRepository = fixture.repository();
                    VcRevision.Ref branch = targetRepository.ref(REF);
                    try (GitFileAccess access = targetRepository.files().open(branch)) {
                        access.write(MATERIAL_PATH, open(external).readContent());
                        VcRevision.Stored created =
                                access.commit(
                                        new VcChangeDescription(
                                                VcChangeAuthor.EMPTY, "external KMS update"));
                        targetRepository.refUpdate(branch, access.revision(), created);
                    }
                }
                testRuntime.awaitEvents();
                assertThat(running.contains(original)).isTrue();
                assertThat(running.contains(added)).isFalse();
                assertThat(running.contains(local)).isTrue();
                assertThat(running.revision()).isEqualTo(localRevision);
                try (OrionKeyMaterial reopened = OrionKeyMaterial.open(new KeyMaterialRepository(
                        fixture.store(),
                        new KeyMaterialCodec(options),
                        testRuntime.unstartedEvents()))) {
                    assertThat(reopened.contains(original)).isTrue();
                    assertThat(reopened.contains(added)).isTrue();
                    assertThat(reopened.contains(local)).isFalse();
                    assertThat(reopened.revision()).isEqualTo(open(fixture.store()).revision().value());
                }
            }
        }
    }

    @Test
    void reloadsCommittedMaterialAndRetainsItWhenTheNextUpdateIsCorrupt() throws Exception {
        KeyMaterialId original = new KeyMaterialId("original", 1);
        KeyMaterialId added = new KeyMaterialId("added", 1);
        InMemoryBlobStorage external = emptyMaterialStore();
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray());
             OrionKeyMaterial writer = OrionKeyMaterial.open(new KeyMaterialRepository(
                     external, new KeyMaterialCodec(options), testRuntime.unstartedEvents()))) {
            try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                modification.generateKey("original", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            Fixture fixture = fixture(Map.of(MATERIAL_PATH, open(external).readContent()));
            try (OrionKeyMaterial running = OrionKeyMaterial.open(new KeyMaterialRepository(
                    fixture.store(), new KeyMaterialCodec(options), testRuntime.events()))) {
                pro.deta.orion.keymaterial.KeyMaterialOperation stale = running.open(new VcRevision.Stored(running.revision()));
                try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                    modification.generateKey("added", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                    modification.commit(VcChangeDescription.EMPTY);
                }
                {
                    NativeGitRepository targetRepository = fixture.repository();
                    VcRevision.Ref branch = targetRepository.ref(REF);
                    try (GitFileAccess access = targetRepository.files().open(branch)) {
                        access.write(MATERIAL_PATH, open(external).readContent());
                        VcRevision.Stored created =
                                access.commit(
                                        new VcChangeDescription(
                                                VcChangeAuthor.EMPTY, "append material"));
                        targetRepository.refUpdate(branch, access.revision(), created);
                    }
                }
                testRuntime.awaitEvents();
                assertThat(running.contains(added)).isTrue();
                String saved = fixture.repository().refs().get(REF);
                assertThat(running.revision()).isEqualTo(saved);
                assertThatThrownBy(() -> stale.commit(VcChangeDescription.EMPTY))
                        .isInstanceOf(java.io.UncheckedIOException.class)
                        .hasCauseInstanceOf(BlobStorageConflictException.class);

                {
                    NativeGitRepository targetRepository = fixture.repository();
                    VcRevision.Ref branch = targetRepository.ref(REF);
                    try (GitFileAccess access = targetRepository.files().open(branch)) {
                        access.write(MATERIAL_PATH, new byte[] {1, 2, 3});
                        VcRevision.Stored created =
                                access.commit(
                                        new VcChangeDescription(
                                                VcChangeAuthor.EMPTY, "corrupt material"));
                        targetRepository.refUpdate(branch, access.revision(), created);
                    }
                }
                testRuntime.awaitEvents();
                assertThat(running.contains(original)).isTrue();
                assertThat(running.contains(added)).isTrue();
                assertThat(running.revision()).isEqualTo(saved);
                assertThat(running.sign(original, new byte[]{1})).isNotEmpty();
            }
        }
    }

    private Fixture fixture(Map<String, byte[]> initialFiles) throws Exception {
        NativeGitRepositoryFactory factory = new NativeGitRepositoryFactory(
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events()));
        NativeGitRepository repository = factory.provider().create(REPOSITORY).valueOrFailure("create repository");
        {
            NativeGitRepository targetRepository = repository;
            seedEmptyCommit(targetRepository);
            VcRevision.Ref branch = targetRepository.ref(REF);
            try (GitFileAccess fileAccess = targetRepository.files().open(branch)) {
                for (Map.Entry<String, byte[]> fileEntry : initialFiles.entrySet()) {
                    fileAccess.write(fileEntry.getKey(), fileEntry.getValue());
                }
                VcRevision.Stored created =
                        fileAccess.commit(
                                new VcChangeDescription(VcChangeAuthor.EMPTY, "seed repository"));
                targetRepository.refUpdate(branch, fileAccess.revision(), created);
            }
        }
        testRuntime.own(factory.provider());
        testRuntime.awaitEvents();
        return new Fixture(repository, factory.provider());
    }

    private BlobStorageOperations open(BlobStorage store) {
        BlobStorageOperations operation = store.open(new VcRevision.Latest());
        testRuntime.own(operation);
        return operation;
    }

    private static VcRevision.Stored save(BlobStorage store, VcRevision.ReadTarget revision, byte[] content) {
        return save(store, revision, content, VcChangeDescription.EMPTY);
    }

    private static VcRevision.Stored save(BlobStorage store, VcRevision.ReadTarget revision, byte[] content,
                                         VcChangeDescription description) {
        try (BlobStorageOperations operation = store.open(revision)) {
            operation.write(content);
            return operation.commit(description);
        }
    }

    private static byte[] readGit(NativeGitRepository repository, String path) throws Exception {
        try (GitFileAccess access = repository.files().open(repository.ref(REF))) {
            return access.readBytes(path);
        }
    }

    private static void seedEmptyCommit(NativeGitRepository repository) throws Exception {
        ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
        String text = "tree " + tree.toHex() + "\n"
                + "author Test <test@example.invalid> 1 +0000\n"
                + "committer Test <test@example.invalid> 1 +0000\n\nseed\n";
        ObjectId commit = repository.writeObject(GitObjectType.COMMIT, bytes(text));
        pro.deta.orion.git.nativestorage.GitOperationException.requireSuccess(
                List.of(repository.updateRef(REF, "0".repeat(40), commit.toHex())));
    }

    private InMemoryBlobStorage emptyMaterialStore() {
        return testRuntime.pkcs12Store("test-password".toCharArray());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
            NativeGitRepository repository,
            NativeGitRepositoryProvider provider) {
        private GitBlobStorage store() {
            return new GitBlobStorage(new GitFileEvents(
                    new GitFile(provider, REPOSITORY, REF, MATERIAL_PATH)));
        }
    }

    private final class InterleavingProvider extends NativeGitRepositoryProvider {
        @Override
        public void close() {
            delegate.close();
        }

        private final NativeGitRepositoryProvider delegate;
        private final Runnable beforePublish;
        private boolean interleaved;

        private InterleavingProvider(NativeGitRepositoryProvider delegate, Runnable beforePublish) {
            super(pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.inMemory(testRuntime.events()));
            this.delegate = delegate;
            this.beforePublish = beforePublish;
        }

        @Override
        public NativeGitRepository retained(String repositoryName) {
            return delegate.retained(repositoryName);
        }

        @Override
        public boolean exists(String repositoryName) {
            return delegate.exists(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> find(String repositoryName) {
            return delegate.find(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> create(String repositoryName) {
            return delegate.create(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> openForWrite(String repositoryName) {
            if (!interleaved) {
                interleaved = true;
                beforePublish.run();
            }
            return delegate.openForWrite(repositoryName);
        }
    }
}
