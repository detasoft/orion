package pro.deta.orion.git.s3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.RefSelection;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class S3GitIndexTest {
    @Test
    void conditionalCollisionMergesDifferentRefsAndRejectsTheSameRef() throws Exception {
        for (boolean sameRef : List.of(false, true)) {
            try (Wire wire = new Wire(); S3Transport transport = new S3Transport();
                 ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                S3RepositoryObjects objects = wire.objects(transport);
                try (S3GitIndexApi first = new S3GitIndexApi(objects); S3GitIndexApi second = new S3GitIndexApi(objects)) {
                    RefId left = new RefId("refs/heads/left");
                    RefId right = sameRef ? left : new RefId("refs/heads/right");
                    GitIndexAccess a = first.createAccess(List.of(new RefUpdate(left, Optional.empty(),
                            Optional.of(new ObjectId("a".repeat(40))))));
                    GitIndexAccess b = second.createAccess(List.of(new RefUpdate(right, Optional.empty(),
                            Optional.of(new ObjectId("b".repeat(40))))));
                    wire.collide = new CyclicBarrier(2);
                    Future<Boolean> one = executor.submit(() -> apply(a));
                    Future<Boolean> two = executor.submit(() -> apply(b));
                    int successes = (one.get(10, TimeUnit.SECONDS) ? 1 : 0) + (two.get(10, TimeUnit.SECONDS) ? 1 : 0);
                    assertThat(successes).isEqualTo(sameRef ? 1 : 2);
                    assertThat(wire.conflicts).hasValue(1);
                    assertThat(first.activeAccesses()).isEmpty();
                    assertThat(second.activeAccesses()).isEmpty();
                    first.withAccess(access -> {
                        assertThat(access.snapshotRefs(new RefSelection.All()).refs()).hasSize(sameRef ? 1 : 2);
                        assertThat(access.findRef(left)).isPresent();
                        assertThat(access.snapshotRefs(new RefSelection.Pattern("refs/heads/l*")).refs())
                                .containsOnlyKeys(left);
                        assertThat(access.snapshotRefs(new RefSelection.Substring("right")).refs())
                                .hasSize(sameRef ? 0 : 1);
                        return null;
                    });
                }
            }
        }
    }

    @Test
    void observesBoundPackAccessUntilCompletionAndRejectsOtherPackWrites() throws Exception {
        try (Wire wire = new Wire(); S3Transport transport = new S3Transport();
             S3GitIndexApi owner = new S3GitIndexApi(wire.objects(transport))) {
            PackId pack = PackId.create();
            IndexedObject object = new IndexedObject(pack, new ObjectId("a".repeat(40)), GitObjectType.BLOB,
                    1, 0, 4, Optional.empty());
            PackMetadata metadata = new PackMetadata(pack, new PackChecksum("b".repeat(40)),
                    pack.toString(), 1, 40);
            GitIndexAccess reader = owner.createAccess();
            GitIndexAccess writer = owner.createAccess(Optional.of(pack));
            try {
                assertThat(reader.packId()).isEmpty();
                assertThat(writer.packId()).contains(pack);
                java.util.Set<GitIndexAccess> active = owner.activeAccesses();
                assertThat(active).containsExactlyInAnyOrder(reader, writer);
                assertThatThrownBy(active::clear).isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(() -> reader.addObject(object)).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> reader.publishIndex(metadata)).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> writer.addObject(new IndexedObject(PackId.create(), object.objectId(),
                        GitObjectType.BLOB, 1, 0, 4, Optional.empty()))).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> writer.publishIndex(new PackMetadata(PackId.create(),
                        metadata.packChecksum(), "other", 0, 32))).isInstanceOf(IOException.class);
                owner.close();
                assertThatThrownBy(owner::createAccess).isInstanceOf(IOException.class);
                writer.addObject(object);
                writer.publishIndex(metadata);
                writer.apply();
                assertThat(owner.activeAccesses()).containsExactly(reader);
                assertThat(active).containsExactlyInAnyOrder(reader, writer);
            } finally {
                writer.discard();
                reader.discard();
            }
            assertThat(owner.activeAccesses()).isEmpty();
        }
    }

    @Test
    void duplicateObjectLookupUsesTheFirstPackPositionBeforeAndAfterPublication() throws Exception {
        try (Wire wire = new Wire(); S3Transport transport = new S3Transport();
             S3GitIndexApi index = new S3GitIndexApi(wire.objects(transport))) {
            PackId pack = PackId.create();
            ObjectId id = new ObjectId("a".repeat(40));
            IndexedObject first = new IndexedObject(pack, id, GitObjectType.BLOB, 1, 0, 4, Optional.empty());
            IndexedObject second = new IndexedObject(pack, id, GitObjectType.BLOB, 1, 4, 4, Optional.empty());
            index.withAccess(Optional.of(pack), access -> {
                access.addObject(second);
                access.addObject(first);
                assertThat(access.findObject(pack, id)).contains(first);
                assertThat(access.findObject(pack, new ObjectId("c".repeat(40)))).isEmpty();
                return null;
            });
            index.withAccess(Optional.of(pack), access -> {
                assertThat(access.findObject(pack, id)).contains(first);
                access.publishIndex(new PackMetadata(pack, new PackChecksum("b".repeat(40)),
                        pack.toString(), 2, 40));
                return null;
            });
            try (S3GitIndexApi reopened = new S3GitIndexApi(wire.objects(transport))) {
                reopened.withAccess(access -> {
                    assertThat(access.findObject(pack, id)).contains(first);
                    assertThat(access.locations(id)).containsExactly(first, second);
                    return null;
                });
            }
        }
    }

    @Test
    void malformedRefContentFailsInsteadOfPretendingTheRepositoryIsEmpty() throws Exception {
        try (Wire wire = new Wire(); S3Transport transport = new S3Transport();
             S3GitIndexApi index = new S3GitIndexApi(wire.objects(transport))) {
            wire.contents.put("/bucket/repo/refs", new byte[]{0, 1, 2, 3});
            assertThatThrownBy(index::createAccess).isInstanceOf(IOException.class).hasMessageContaining("refs");
        }
    }

    @Test
    void loadsPublishedIndexesOnceAcrossAccessesAndReloadsAfterRestart() throws Exception {
        try (Wire wire = new Wire(); S3Transport transport = new S3Transport()) {
            PackId pack = PackId.create();
            IndexedObject object = object(pack);
            wire.store(manifest(object));
            for (int restart = 0; restart < 2; restart++) {
                try (S3GitIndexApi owner = new S3GitIndexApi(wire.objects(transport))) {
                    for (int iteration = 0; iteration < 3; iteration++) {
                        owner.withAccess(access -> {
                            assertThat(access.locations(object.objectId())).containsExactly(object);
                            assertThat(access.findObject(pack, object.objectId())).contains(object);
                            assertThat(access.objects(pack)).containsExactly(object);
                            assertThat(access.findPack(pack)).contains(manifest(object).pack());
                            assertThat(access.packs()).containsExactly(manifest(object).pack());
                            assertThat(access.locations(new ObjectId("c".repeat(40)))).isEmpty();
                            return null;
                        });
                    }
                }
                assertThat(wire.indexLists).hasValue(restart + 1);
                assertThat(wire.indexGets).hasValue(restart + 1);
            }
        }
    }

    @Test
    void publishesIntoEveryAccessOnlyAfterDurablePublication() throws Exception {
        try (Wire wire = new Wire(); S3Transport transport = new S3Transport();
             S3GitIndexApi owner = new S3GitIndexApi(wire.objects(transport))) {
            GitIndexAccess reader = owner.createAccess();
            IndexedObject object = object(PackId.create());
            assertThat(reader.locations(object.objectId())).isEmpty();
            GitIndexAccess writer = owner.createAccess(Optional.of(object.packId()));
            try (AutoCloseable completion = writer::discard) {
                writer.addObject(object);
                wire.rejectIndexes = true;
                assertThatThrownBy(() -> writer.publishIndex(manifest(object).pack())).isInstanceOf(IOException.class);
                assertThat(reader.locations(object.objectId())).isEmpty();
                assertThat(reader.packs()).isEmpty();
                wire.rejectIndexes = false;
                writer.publishIndex(manifest(object).pack());
                assertThat(reader.locations(object.objectId())).containsExactly(object);
                assertThat(reader.findPack(object.packId())).contains(manifest(object).pack());
                writer.publishIndex(manifest(object).pack());
                assertThat(reader.locations(object.objectId())).containsExactly(object);
            }
            assertThat(wire.indexLists).hasValue(1);
            assertThat(wire.indexGets).hasValue(0);
            reader.discard();
        }
    }

    @Test
    void retriesTheWholeLoadAfterAMalformedManifestWithoutRetainingPartialState() throws Exception {
        try (Wire wire = new Wire(); S3Transport transport = new S3Transport();
             S3GitIndexApi owner = new S3GitIndexApi(wire.objects(transport))) {
            GitIndexAccess access = owner.createAccess();
            IndexedObject first = object(new PackId("00000000-0000-0000-0000-000000000001"));
            IndexedObject second = object(new PackId("00000000-0000-0000-0000-000000000002"));
            wire.store(manifest(first));
            wire.contents.put("/bucket/repo/" + S3GitIndexApi.key(second.packId()), new byte[]{0, 1});
            assertThatThrownBy(() -> access.locations(first.objectId())).isInstanceOf(IOException.class);
            wire.contents.remove("/bucket/repo/" + S3GitIndexApi.key(first.packId()));
            wire.store(manifest(second));
            assertThat(access.locations(first.objectId())).containsExactly(second);
            assertThat(access.packs()).containsExactly(manifest(second).pack());
            assertThat(wire.indexLists).hasValue(2);
            assertThat(wire.indexGets).hasValue(3);
            access.discard();
        }
    }

    @Test
    void publicationRacingTheInitialLoadRemainsVisibleToAllAccesses() throws Exception {
        try (Wire wire = new Wire(); S3Transport transport = new S3Transport();
             S3GitIndexApi owner = new S3GitIndexApi(wire.objects(transport));
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            GitIndexAccess reader = owner.createAccess();
            IndexedObject object = object(PackId.create());
            GitIndexAccess writer = owner.createAccess(Optional.of(object.packId()));
            wire.listEntered = new CountDownLatch(1);
            wire.listRelease = new CountDownLatch(1);
            Future<?> load = executor.submit(() -> reader.locations(object.objectId()));
            Future<?> publish;
            try {
                assertThat(wire.listEntered.await(5, TimeUnit.SECONDS)).isTrue();
                publish = executor.submit(() -> {
                    try {
                        writer.addObject(object);
                        return writer.publishIndex(manifest(object).pack());
                    } finally {
                        writer.discard();
                    }
                });
                executor.submit(owner::close).get(1, TimeUnit.SECONDS);
                assertThatThrownBy(owner::createAccess).isInstanceOf(IOException.class);
            } finally {
                wire.listRelease.countDown();
            }
            load.get(5, TimeUnit.SECONDS);
            publish.get(5, TimeUnit.SECONDS);
            assertThat(reader.locations(object.objectId())).containsExactly(object);
            assertThat(reader.packs()).containsExactly(manifest(object).pack());
            assertThat(wire.indexLists).hasValue(1);
            reader.discard();
        }
    }

    private static IndexedObject object(PackId pack) {
        return new IndexedObject(pack, new ObjectId("a".repeat(40)), GitObjectType.BLOB,
                1, 0, 4, Optional.empty());
    }

    private static S3GitIndexApi.Manifest manifest(IndexedObject object) {
        return new S3GitIndexApi.Manifest(new PackMetadata(object.packId(), new PackChecksum("b".repeat(40)),
                object.packId().toString(), 1, 40), List.of(object));
    }

    private static boolean apply(GitIndexAccess access) throws IOException {
        try {
            access.apply();
            return true;
        } catch (GitRefConflictException expected) {
            return false;
        } finally {
            access.discard();
        }
    }

    static final class Wire implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final Map<String, byte[]> contents = new ConcurrentHashMap<>();
        final Map<String, String> etags = new ConcurrentHashMap<>();
        final AtomicInteger conflicts = new AtomicInteger();
        final AtomicInteger refPuts = new AtomicInteger();
        final AtomicInteger abortedUploads = new AtomicInteger();
        final List<String> authorizations = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger indexLists = new AtomicInteger();
        final AtomicInteger indexGets = new AtomicInteger();
        volatile CountDownLatch listEntered;
        volatile CountDownLatch listRelease;
        volatile CyclicBarrier collide;
        volatile boolean rejectIndexes;
        volatile boolean truncateRange;
        volatile boolean rejectMultipart;
        volatile boolean rejectAbort;

        Wire() throws IOException {
            server.setExecutor(executor);
            server.createContext("/", this::request);
            server.start();
        }

        S3RepositoryObjects objects(S3Transport transport) {
            return new S3RepositoryObjects(transport, () -> transport.overrides(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "us-east-1", true,
                    Optional.of(StaticCredentialsProvider.create(AwsBasicCredentials.create("id", "key")))),
                    "bucket", "repo/");
        }

        void store(S3GitIndexApi.Manifest manifest) throws IOException {
            contents.put("/bucket/repo/" + S3GitIndexApi.key(manifest.pack().packId()), S3GitIndexApi.encode(manifest));
        }

        private void request(HttpExchange exchange) throws IOException {
            String key = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String query = exchange.getRequestURI().getQuery();
            if (rejectMultipart && query != null && (query.contains("uploads") || query.contains("uploadId="))) {
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                if (method.equals("POST")) {
                    reply(exchange, 200, ("<InitiateMultipartUploadResult><UploadId>test-upload</UploadId>"
                            + "</InitiateMultipartUploadResult>").getBytes(StandardCharsets.UTF_8));
                } else if (method.equals("DELETE")) {
                    abortedUploads.incrementAndGet();
                    reply(exchange, rejectAbort ? 403 : 204, rejectAbort
                            ? "<Error><Code>AccessDenied</Code></Error>".getBytes(StandardCharsets.UTF_8) : new byte[0]);
                } else {
                    reply(exchange, 403, "<Error><Code>AccessDenied</Code></Error>".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("aws-chunked".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) body = chunks(body);
            if (method.equals("PUT")) {
                if (key.endsWith("/refs") && collide != null && refPuts.incrementAndGet() <= 2) {
                    try { collide.await(5, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IOException(failure); }
                }
                if (rejectIndexes && key.endsWith(".index")) {
                    reply(exchange, 403, "<Error><Code>AccessDenied</Code></Error>".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                synchronized (contents) {
                    String expected = exchange.getRequestHeaders().getFirst("If-Match");
                    boolean absent = "*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"));
                    if ((absent && contents.containsKey(key)) || (expected != null && !expected.equals(etags.get(key)))) {
                        conflicts.incrementAndGet();
                        reply(exchange, 412, "<Error><Code>PreconditionFailed</Code></Error>"
                                .getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    contents.put(key, body);
                    String etag = "\"" + md5(body) + "\"";
                    etags.put(key, etag);
                    exchange.getResponseHeaders().set("ETag", etag);
                }
                reply(exchange, 200, new byte[0]);
                return;
            }
            if (method.equals("DELETE")) {
                contents.remove(key);
                etags.remove(key);
                reply(exchange, 204, new byte[0]);
                return;
            }
            if (exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("list-type=2")) {
                StringBuilder xml = new StringBuilder("<ListBucketResult><IsTruncated>false</IsTruncated>");
                indexLists.incrementAndGet();
                java.util.ArrayList<String> keys = new java.util.ArrayList<>(contents.keySet());
                keys.sort(String::compareTo);
                if (listEntered != null) {
                    listEntered.countDown();
                    try {
                        if (!listRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out releasing LIST");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                }
                String prefix = "";
                for (String parameter : query.split("&")) {
                    if (parameter.startsWith("prefix=")) {
                        prefix = java.net.URLDecoder.decode(parameter.substring(7), StandardCharsets.UTF_8);
                    }
                }
                for (String stored : keys) {
                    if (!stored.startsWith("/bucket/" + prefix)) continue;
                    xml.append("<Contents><Key>").append(stored.substring("/bucket/".length())).append("</Key></Contents>");
                }
                reply(exchange, 200, xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (key.endsWith(".index")) indexGets.incrementAndGet();
            byte[] data = contents.get(key);
            if (data == null) {
                reply(exchange, 404, "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().set("ETag", etags.getOrDefault(key, "\"0\""));
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                String[] bounds = range.substring("bytes=".length()).split("-");
                int from = Integer.parseInt(bounds[0]);
                int to = Integer.parseInt(bounds[1]);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + data.length);
                exchange.sendResponseHeaders(206, to - from + 1);
                exchange.getResponseBody().write(data, from, truncateRange ? 1 : to - from + 1);
                exchange.close();
                return;
            }
            reply(exchange, 200, data);
        }

        private static String md5(byte[] bytes) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private static byte[] chunks(byte[] bytes) throws IOException {
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            for (;;) {
                StringBuilder line = new StringBuilder();
                int value;
                while ((value = input.read()) != '\n' && value >= 0) if (value != '\r') line.append((char) value);
                int size = Integer.parseInt(line.toString().split(";", 2)[0], 16);
                if (size == 0) return decoded.toByteArray();
                decoded.write(input.readNBytes(size));
                input.skipNBytes(2);
            }
        }

        private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        public void close() { server.stop(0); executor.close(); }
    }
}
