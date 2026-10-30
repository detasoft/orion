package pro.deta.orion;

import pro.deta.orion.git.fileapi.GitBlobStorage;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.BlobChanged;

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
import pro.deta.orion.storage.BlobStorage.Content;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.acme.AcmeCertificateService;
import pro.deta.orion.config.NativeGitOrionConfigurationStorage;
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

import pro.deta.orion.git.fileapi.GitCommitAuthor;

import pro.deta.orion.git.fileapi.GitCommitMetadata;

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
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
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
                     new GitFileEvents(new GitFile(provider, REPOSITORY, REF, "orion.xml")),
                     GitCommitMetadata.EMPTY);
             GitBlobStorage material = fixture.store()) {
            List<BlobChanged> changes = new CopyOnWriteArrayList<>();
            pro.deta.orion.util.Subscription subscription = configuration.subscribe(changes::add);
            String previous = configuration.load().revision();
            material.write(new BlobStorage.Modification(material.load().revision(), bytes("other material")));
            testRuntime.awaitEvents();
            assertThat(changes).isEmpty();
            String saved = configuration.write(new BlobStorage.Modification(previous, bytes("<orion />")));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(BlobChanged::revision).containsExactly(saved);
            assertThat(changes.getFirst().oldRevision()).isEqualTo(previous);
            String next = configuration.write(new BlobStorage.Modification(saved, bytes("<next/>")));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(BlobChanged::revision).containsExactly(saved, next);
            assertThat(changes.getLast().oldRevision()).isEqualTo(previous);
            configuration.read(next);
            String accepted = configuration.write(new BlobStorage.Modification(next, bytes("<accepted/>")));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(BlobChanged::revision).containsExactly(saved, next, accepted);
            assertThat(changes.getLast().oldRevision()).isEqualTo(next);
            material.write(new BlobStorage.Modification(material.load().revision(), bytes("updated material")));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(BlobChanged::revision).containsExactly(saved, next, accepted);
            subscription.close();
            configuration.write(new BlobStorage.Modification(configuration.load().revision(),
                    bytes("<closed/>")));
            testRuntime.awaitEvents();
            assertThat(changes).extracting(BlobChanged::revision).containsExactly(saved, next, accepted);
        }
    }

    @Test
    void reportsNoOldRevisionWhenCreatingANewBranch() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("<orion/>")));
        try (NativeGitRepositoryProvider provider = fixture.provider();
             GitBlobStorage storage = new GitBlobStorage(new GitFileEvents(
                     new GitFile(provider, REPOSITORY, "refs/heads/new", "orion.xml")),
                             GitCommitMetadata.EMPTY)) {
            List<BlobChanged> changes = new CopyOnWriteArrayList<>();
            try (pro.deta.orion.util.Subscription ignored = storage.subscribe(changes::add)) {
                String saved = storage.write(new BlobStorage.Modification(null, bytes("<orion />")));
                testRuntime.awaitEvents();
                assertThat(changes).extracting(BlobChanged::revision).containsExactly(saved);
                assertThat(changes.getFirst().oldRevision()).isNull();
            }
        }
    }

    @Test
    void storesOpaqueXmlWithCallerCommitMetadata() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("<orion/>")));
        GitCommitMetadata metadata = new GitCommitMetadata(
                new GitCommitAuthor("Configuration editor", "editor@example.test"), "Update configuration");
        try (NativeGitRepositoryProvider provider = fixture.provider();
             GitBlobStorage storage = new GitBlobStorage(
                     new GitFileEvents(new GitFile(provider, REPOSITORY, REF, "orion.xml")), metadata)) {
            BlobStorage.Content initial = storage.load();
            String saved = storage.write(new BlobStorage.Modification(initial.revision(), bytes("<orion />")));
            assertThat(storage.read(saved)).isEqualTo(bytes("<orion />"));
            assertThat(initial.bytes()).isEqualTo(bytes("<orion/>"));
            byte[] commit = fixture.repository().readObject(
                    new pro.deta.orion.git.parser.v2.id.ObjectId(saved)).orElseThrow().data();
            assertThat(new String(commit, StandardCharsets.UTF_8))
                    .contains("author Configuration editor <editor@example.test>")
                    .endsWith("Update configuration\n");
        }
    }

    @Test
    void activatesAnIssuedCertificateWhenConfigurationAndMaterialShareTheGitBranch() throws Exception {
        KeyMaterialDescriptor account = new KeyMaterialDescriptor("account",
                Set.of(KeyMaterialPurpose.ACME_ACCOUNT),
                KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor identity = new KeyMaterialDescriptor("identity",
                Set.of(KeyMaterialPurpose.TLS_IDENTITY),
                KeyMaterialAlgorithm.RSA, 1);
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(true,
                URI.create("acme://letsencrypt.org/staging"),
                "admin@example.test", List.of("example.test"), "ORION", Optional.of(account.id()),
                30, 40, true, false, Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 8443, null,
                Optional.of(identity.id()), Optional.empty(),
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
             NativeGitOrionConfigurationStorage storage = new NativeGitOrionConfigurationStorage(new GitFileEvents(
                     new GitFile(provider, REPOSITORY, REF, "orion.xml")))) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(account, 2048);
                modification.generateKey(identity, 2048);
                modification.apply();
            });
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
                            executor.submit(() -> activeStore.get().load()).get(2,
                                    java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new AssertionError("Store read is blocked by ref event publication", failure);
                        }
                    }
                    super.publish(event);
                }
            };
            events.onStart();
            try (NativeGitRepositoryProvider provider = NativeGitRepositoryProvider.inMemory(events)) {
                provider.create(REPOSITORY).valueOrFailure("create repository");
                try (GitBlobStorage store =
                        new GitBlobStorage(new GitFileEvents(
                                new GitFile(
                                    provider,
                                    REPOSITORY,
                                    REF,
                                    MATERIAL_PATH)), new GitCommitMetadata(GitCommitAuthor.EMPTY,
                                            "Update server identity material"))) {
                    activeStore.set(store);
                    String saved = store.write(new BlobStorage.Modification(null, bytes("material")));
                    assertThat(store.load().revision()).isEqualTo(saved);
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
        String base = fixture.repository().refs().get(REF);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray());
             OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                     source, new KeyMaterialCodec(options), testRuntime.events()))) {
            assertThat(material.revision()).isEqualTo(base);
            assertThat(material.hasDurableSnapshot()).isFalse();
            KeyMaterialDescriptor signing = new KeyMaterialDescriptor("signing",
                    Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
            pro.deta.orion.keymaterial.KeyMaterialModification modification =
                    material.modify(material.revision());
            assertThat(modification.version()).isEqualTo(base);
            modification.generateKey(signing, 2048);
            modification.apply();
            assertThat(material.revision()).isEqualTo(source.load().revision()).isNotEqualTo(base);
            assertThat(material.hasDurableSnapshot()).isTrue();
            assertThat(material.contains(signing)).isTrue();
            assertThat(fixture.repository().files().readBytes(REF, "orion.xml"))
                    .isEqualTo(bytes("configuration"));
        }
    }

    @Test
    void createsMissingMaterialOnExistingBranchWithoutReplacingConfiguration() throws Exception {
        Fixture fixture = fixture(Map.of("orion.xml", bytes("configuration")));
        GitBlobStorage store = fixture.store();

        String base = fixture.repository().refs().get(REF);
        Content initial = store.load();
        assertThat(initial.revision()).isEqualTo(base);
        assertThat(initial.bytes()).isNull();
        assertThatThrownBy(() -> store.read(base)).isInstanceOf(java.io.FileNotFoundException.class);
        String version = store.write(new BlobStorage.Modification(base, bytes("encrypted-material")));

        assertThat(store.load().revision()).isEqualTo(version);
        assertThat(fixture.repository().files().readBytes(REF, "orion.xml")).isEqualTo(bytes("configuration"));
        assertThat(fixture.repository().files().readBytes(REF, MATERIAL_PATH))
                .isEqualTo(bytes("encrypted-material"));
        assertThatThrownBy(() -> store.write(new BlobStorage.Modification(null,
                bytes("unexpected replacement"))))
                .isInstanceOf(BlobStorageConflictException.class);
    }

    @Test
    void preservesUnrelatedConfigurationChangesWithoutReloadingMaterial() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial"),
                "orion.xml", bytes("initial config")));
        GitBlobStorage store = fixture.store();
        Content initial = store.load();
        String version = initial.revision();
        fixture.repository()
                .files()
                .withAccess(
                        REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "configuration update"),
                        fileAccess -> {
                            fileAccess.write("orion.xml", bytes("new config"));
                            fileAccess.apply();
                            return null;
                        });

        assertThat(initial.bytes()).isEqualTo(bytes("initial"));
        assertThat(store.load().revision()).isNotEqualTo(version);
        String saved = store.write(new BlobStorage.Modification(version, bytes("updated material")));

        assertThat(store.load().revision()).isEqualTo(saved);
        assertThat(fixture.repository().files().readBytes(REF, "orion.xml")).isEqualTo(bytes("new config"));
        assertThat(fixture.repository().files().readBytes(REF, MATERIAL_PATH))
                .isEqualTo(bytes("updated material"));
    }

    @Test
    void mapsAStaleProviderPublicationToMaterialStoreConflict() throws Exception {
        Fixture fixture = fixture(Map.of(MATERIAL_PATH, bytes("initial")));
        GitBlobStorage first = fixture.store();
        GitBlobStorage second = fixture.store();
        String firstRevision = first.load().revision();

        String secondRevision = second.load().revision();

        first.write(new BlobStorage.Modification(firstRevision, bytes("first")));

        assertThatThrownBy(() -> second.write(new BlobStorage.Modification(secondRevision, bytes("second"))))
                .isInstanceOf(BlobStorageConflictException.class)
                .hasMessage("Blob store changed before save");
        assertThat(first.load().bytes()).isEqualTo(bytes("first"));
    }

    @Test
    void rejectsAnUpdateCommittedBetweenPreparationAndPublication() throws Exception {
        NativeGitRepositoryProvider backend = NativeGitRepositoryProvider.inMemory(testRuntime.events());
        NativeGitRepository repository = backend.create(REPOSITORY).valueOrFailure("create repository");
        repository
                .files()
                .withAccess(
                        REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed repository"),
                        fileAccess -> {
                            fileAccess.write(MATERIAL_PATH, bytes("initial"));
                            fileAccess.apply();
                            return null;
                        });
        InterleavingProvider provider =
                new InterleavingProvider(
                        backend,
                        () -> {
                            try {
                                backend.openForWrite(REPOSITORY)
                                        .valueOrFailure("repository")
                                        .files()
                                        .withAccess(
                                                REF,
                                                new GitCommitMetadata(
                                                        GitCommitAuthor.EMPTY, "concurrent update"),
                                                fileAccess -> {
                                                    fileAccess.write("orion.xml",
                                                            bytes("concurrent configuration"));
                                                    fileAccess.write(MATERIAL_PATH,
                                                            bytes("concurrent material"));
                                                    fileAccess.apply();
                                                    return null;
                                                });
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        });
        GitBlobStorage store = new GitBlobStorage(new GitFileEvents(
                new GitFile(
                    provider,
                    REPOSITORY,
                    REF,
                    MATERIAL_PATH)), new GitCommitMetadata(GitCommitAuthor.EMPTY,
                            "Update server identity material"));
        String observedVersion = store.load().revision();

        assertThatThrownBy(() -> store.write(new BlobStorage.Modification(observedVersion,
                bytes("stale material"))))
                .isInstanceOf(BlobStorageConflictException.class);

        assertThat(repository.files().readBytes(REF, MATERIAL_PATH)).isEqualTo(bytes("concurrent material"));
        assertThat(repository.files().readBytes(REF, "orion.xml")).isEqualTo(bytes("concurrent configuration"));
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
                    NativeGitRepositoryFactory.repositoryPath(source.getPath()))),
                            new GitCommitMetadata(GitCommitAuthor.EMPTY, "Update server identity material"));

        String base = store.load().revision();
        assertThatThrownBy(() -> store.read(base)).isInstanceOf(java.io.FileNotFoundException.class);
        store.write(new BlobStorage.Modification(base, bytes("encrypted-material")));

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
            List<String> revisions = new CopyOnWriteArrayList<>();
            GitFile file = new GitFile(fixture.provider(), REPOSITORY, REF, MATERIAL_PATH);
            GitFileEvents source = new GitFileEvents(file);
            testRuntime.own(source);
            testRuntime.own(source.events().subscribe(GitFileChanged.class, event -> {
                if (source.matches(event)) revisions.add(event.revision().toHex());
            }));
            fixture.repository().files().withAccess(REF,
                    new GitCommitMetadata(GitCommitAuthor.EMPTY, "unrelated file"), access -> {
                        access.write("orion.xml", bytes("updated configuration"));
                        access.apply();
                        return null;
                    });
            fixture.repository().files().withAccess("refs/heads/other",
                    new GitCommitMetadata(GitCommitAuthor.EMPTY, "other branch"), access -> {
                        access.write(MATERIAL_PATH, bytes("other branch"));
                        access.apply();
                        return null;
                    });
            testRuntime.awaitEvents();
            assertThat(revisions).isEmpty();
            String first = store.write(new BlobStorage.Modification(store.load().revision(), bytes("first")));
            String second = store.write(new BlobStorage.Modification(first, bytes("second")));
            testRuntime.awaitEvents();
            assertThat(revisions).containsExactly(first, second);
            assertThat(store.read(first)).isEqualTo(bytes("first"));
            assertThat(store.read(second)).isEqualTo(bytes("second"));
        }
    }

    @Test
    void retainsRunningMaterialWhenAnExternalUpdateReplacesItsHistory() throws Exception {
        char[] password = "test-password".toCharArray();
        InMemoryBlobStorage external = new InMemoryBlobStorage();
        KeyMaterialDescriptor original = new KeyMaterialDescriptor("original",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor local = new KeyMaterialDescriptor("local",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor added = new KeyMaterialDescriptor("added",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(password);
             OrionKeyMaterial writer = OrionKeyMaterial.open(new KeyMaterialRepository(
                     external,
                     new KeyMaterialCodec(options),
                     testRuntime.unstartedEvents()))) {
            writer.withModification(writer.revision(), modification -> {
                modification.generateKey(original, 2048);
                modification.apply();
            });
            Fixture fixture = fixture(Map.of(MATERIAL_PATH, external.load().bytes()));
            try (OrionKeyMaterial running = OrionKeyMaterial.open(new KeyMaterialRepository(
                    fixture.store(),
                    new KeyMaterialCodec(options),
                    testRuntime.events()))) {
                running.withModification(running.revision(), modification -> {
                    modification.generateKey(local, 2048);
                    modification.apply();
                });
                testRuntime.awaitEvents();
                String localRevision = running.revision();
                writer.withModification(writer.revision(), modification -> {
                    modification.generateKey(added, 2048);
                    modification.apply();
                });
                fixture.repository().files().withAccess(REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "external KMS update"), access -> {
                            access.write(MATERIAL_PATH, external.load().bytes());
                            access.apply();
                            return null;
                        });
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
                    assertThat(reopened.revision()).isEqualTo(fixture.store().load().revision());
                }
            }
        }
    }

    @Test
    void reloadsCommittedMaterialAndRetainsItWhenTheNextUpdateIsCorrupt() throws Exception {
        KeyMaterialDescriptor original = new KeyMaterialDescriptor("original",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor added = new KeyMaterialDescriptor("added",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1);
        InMemoryBlobStorage external = new InMemoryBlobStorage();
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray());
             OrionKeyMaterial writer = OrionKeyMaterial.open(new KeyMaterialRepository(
                     external, new KeyMaterialCodec(options), testRuntime.unstartedEvents()))) {
            writer.withModification(writer.revision(), modification -> {
                modification.generateKey(original, 2048);
                modification.apply();
            });
            Fixture fixture = fixture(Map.of(MATERIAL_PATH, external.load().bytes()));
            try (OrionKeyMaterial running = OrionKeyMaterial.open(new KeyMaterialRepository(
                    fixture.store(), new KeyMaterialCodec(options), testRuntime.events()))) {
                pro.deta.orion.keymaterial.KeyMaterialModification stale = running.modify(running.revision());
                writer.withModification(writer.revision(), modification -> {
                    modification.generateKey(added, 2048);
                    modification.apply();
                });
                fixture.repository().files().withAccess(REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "append material"), access -> {
                            access.write(MATERIAL_PATH, external.load().bytes());
                            access.apply();
                            return null;
                        });
                testRuntime.awaitEvents();
                assertThat(running.contains(added)).isTrue();
                String saved = fixture.repository().refs().get(REF);
                assertThat(running.revision()).isEqualTo(saved);
                assertThatThrownBy(stale::apply).isInstanceOf(BlobStorageConflictException.class);

                fixture.repository().files().withAccess(REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "corrupt material"), access -> {
                            access.write(MATERIAL_PATH, new byte[]{1, 2, 3});
                            access.apply();
                            return null;
                        });
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
        repository
                .files()
                .withAccess(
                        REF,
                        new GitCommitMetadata(GitCommitAuthor.EMPTY, "seed repository"),
                        fileAccess -> {
                            for (Map.Entry<String, byte[]> fileEntry : initialFiles.entrySet()) {
                                fileAccess.write(fileEntry.getKey(), fileEntry.getValue());
                            }
                            fileAccess.apply();
                            return null;
                        });
        return new Fixture(repository, factory.provider());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
            NativeGitRepository repository,
            NativeGitRepositoryProvider provider) {
        private GitBlobStorage store() {
            return new GitBlobStorage(new GitFileEvents(
                    new GitFile(
                        provider,
                        REPOSITORY,
                        REF,
                        MATERIAL_PATH)), new GitCommitMetadata(GitCommitAuthor.EMPTY,
                                "Update server identity material"));
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
