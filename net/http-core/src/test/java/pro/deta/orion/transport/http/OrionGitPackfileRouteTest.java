package pro.deta.orion.transport.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.NoDeltaPackBuilder;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.pack.PublishedPack;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrionGitPackfileRouteTest {
    private static final PackIngestionLimits LIMITS =
            new PackIngestionLimits(1024 * 1024, 100, 1024 * 1024);

    @TempDir
    private Path tempDir;

    @Test
    void streamsPublishedPackForAuthorizedReader() throws Exception {
        FileNativeGitRepositoryProvider backend =
                new FileNativeGitRepositoryProvider(tempDir);
        NativeGitRepository repository = backend.create("team/project.git")
                .valueOrFailure("repository");
        RecordingProvider provider = new RecordingProvider(backend);
        PublishedPackFixture pack = publishPack(repository);
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(provider);
        ResponseRecorder response = new ResponseRecorder();

        route.handle(
                request(
                        "GET",
                        "/r/team/project.git/objects/pack/"
                                + pack.publishedPack().packId()
                                + ".pack",
                        repositorySecurityContext("team/project")),
                response.proxy(),
                null);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo(OrionGitPackfileRoute.PACK_CONTENT_TYPE);
        assertThat(response.headers).containsEntry("Cache-Control", "no-cache");
        assertThat(response.contentLength).isEqualTo(pack.packBytes().length);
        assertThat(response.body.toByteArray()).isEqualTo(pack.packBytes());
        assertThat(provider.readCalls).isEqualTo(1);
    }

    @Test
    void rejectsInvalidPackIdentifier() throws Exception {
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(
                new FileNativeGitRepositoryProvider(tempDir));
        ResponseRecorder response = new ResponseRecorder();

        route.handle(
                request(
                        "GET",
                        "/r/team/project.git/objects/pack/not-hex.pack",
                        repositorySecurityContext("team/project")),
                response.proxy(),
                null);

        assertThat(response.status)
                .isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    void returnsNotFoundForMissingPack() throws Exception {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(tempDir);
        provider.create("team/project.git").valueOrFailure("repository");
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(provider);
        ResponseRecorder response = new ResponseRecorder();

        route.handle(
                request(
                        "GET",
                        "/r/team/project.git/objects/pack/"
                                + "a".repeat(40)
                                + ".pack",
                        repositorySecurityContext("team/project")),
                response.proxy(),
                null);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void rejectsReaderWithoutRepositoryGrant() throws Exception {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(tempDir);
        NativeGitRepository repository = provider.create("team/project.git")
                .valueOrFailure("repository");
        PublishedPackFixture pack = publishPack(repository);
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(provider);
        ResponseRecorder response = new ResponseRecorder();

        route.handle(
                request(
                        "GET",
                        "/r/team/project.git/objects/pack/"
                                + pack.publishedPack().packId()
                                + ".pack",
                        SecurityContext.createContext()),
                response.proxy(),
                null);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    private static PublishedPackFixture publishPack(
            NativeGitRepository repository) {
        LooseObjectStore sourceObjects = new LooseObjectStore();
        GitObjectId objectId = sourceObjects.write(
                ObjectType.BLOB,
                "published".getBytes(StandardCharsets.UTF_8));
        byte[] packBytes = produce(new NoDeltaPackBuilder().producer(
                sourceObjects,
                List.of(objectId)));
        ByteBuf input = Unpooled.wrappedBuffer(packBytes);
        try {
            PackIngestionSession session =
                    repository.beginPackIngestion(LIMITS);
            PackIngestionResult result = session.accept(input);
            if (result instanceof PackIngestionResult.NeedInput) {
                result = session.endOfInput();
            }
            PackIngestionResult.Complete complete =
                    (PackIngestionResult.Complete) result;
            return new PublishedPackFixture(
                    complete.publishedPack().orElseThrow(),
                    packBytes);
        } finally {
            input.release();
        }
    }

    private static byte[] produce(NativePackProducer producer) {
        ByteBuf output = Unpooled.buffer(128, 1024 * 1024);
        try {
            while (producer.produce(output) == NativePackProducer.Result.MORE) {
            }
            return ByteBufUtil.getBytes(output);
        } finally {
            producer.close();
            output.release();
        }
    }

    private static HttpServletRequest request(
            String method,
            String pathInfo,
            SecurityContext securityContext) {
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) ->
                switch (invokedMethod.getName()) {
                    case "getMethod" -> method;
                    case "getPathInfo" -> pathInfo;
                    case "getAttribute" -> {
                        if (OrionAuthorizationFilter
                                .SECURITY_CONTEXT_ATTRIBUTE
                                .equals(args[0])) {
                            yield securityContext;
                        }
                        yield null;
                    }
                    case "toString" ->
                            "HttpServletRequest[pathInfo=" + pathInfo + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            invokedMethod.toString());
                });
    }

    private static SecurityContext repositorySecurityContext(
            String repositoryName) {
        AccessControl.Grant grant = new AccessControlDraft.Grant(
                "repository",
                new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .toAccessControl();
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl(
                        "git-user",
                        List.of(grant)));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                handler));
    }

    private record PublishedPackFixture(
            PublishedPack publishedPack,
            byte[] packBytes) {
        private PublishedPackFixture {
            packBytes = packBytes.clone();
        }

        @Override
        public byte[] packBytes() {
            return packBytes.clone();
        }
    }

    private static final class RecordingProvider implements NativeGitRepositoryProvider {
        private final NativeGitRepositoryProvider backend;
        private int readCalls;

        private RecordingProvider(NativeGitRepositoryProvider backend) {
            this.backend = backend;
        }

        @Override
        public List<String> repositoryNames() {
            return backend.repositoryNames();
        }

        @Override
        public boolean exists(String repositoryName) {
            return backend.exists(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> find(String repositoryName) {
            return backend.find(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> create(String repositoryName) {
            return backend.create(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> openForRead(String repositoryName) {
            readCalls++;
            return backend.find(repositoryName);
        }
    }

    private static final class ResponseRecorder {
        private int status;
        private String contentType;
        private long contentLength;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "setStatus" -> {
                            status = (int) args[0];
                            yield null;
                        }
                        case "sendError" -> {
                            status = (int) args[0];
                            yield null;
                        }
                        case "setHeader" -> {
                            headers.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "setContentType" -> {
                            contentType = (String) args[0];
                            yield null;
                        }
                        case "setContentLengthLong" -> {
                            contentLength = (long) args[0];
                            yield null;
                        }
                        case "getOutputStream" ->
                                new ByteArrayServletOutputStream(body);
                        case "toString" -> "HttpServletResponseRecorder";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                method.toString());
                    });
        }
    }

    private static final class ByteArrayServletOutputStream
            extends ServletOutputStream {
        private final ByteArrayOutputStream output;

        private ByteArrayServletOutputStream(
                ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }
    }
}
