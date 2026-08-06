package pro.deta.orion.transport.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.NoDeltaPackBuilder;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.pack.PublishedPack;

import java.io.ByteArrayInputStream;
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

class OrionGitRouteNativeTest {
    private static final PackIngestionLimits LIMITS =
            new PackIngestionLimits(1024 * 1024, 100, 1024 * 1024);
    private static final String REPOSITORY_NAME = "team/project";
    private static final String NULL_ID = "0".repeat(40);

    @TempDir
    private Path tempDir;

    @Test
    void discoveryUsesNativeByteBufAdapter() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        publishObject(provider);
        OrionGitRoute route = new OrionGitRoute(provider, autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        route.handle(
                request(
                        "GET",
                        "/r/team/project.git/info/refs",
                        null,
                        "git-upload-pack",
                        Map.of(
                                "Host", "git.example",
                                "Git-Protocol", "version=2"),
                        new byte[0],
                        repositorySecurityContext()),
                response.proxy(),
                null);

        String body = response.body();
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo("application/x-git-upload-pack-advertisement");
        assertThat(response.headers).containsEntry("Cache-Control", "no-cache");
        assertThat(body)
                .contains("# service=git-upload-pack\n0000")
                .contains("version 2\n")
                .contains("fetch=");
    }

    @Test
    void postUsesAutoPackfileUriBaseFromSmartHttpRequest() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        PublishedObjectFixture fixture = publishObject(provider);
        OrionGitRoute route = new OrionGitRoute(provider, autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        route.handle(
                request(
                        "POST",
                        "/r/team/project.git/git-upload-pack",
                        "application/x-git-upload-pack-request",
                        null,
                        Map.of(
                                "Host", "git.example",
                                "Git-Protocol", "version=2"),
                        fetchRequest(fixture.objectId()),
                        repositorySecurityContext()),
                response.proxy(),
                null);

        String body = response.body();
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo("application/x-git-upload-pack-result");
        assertThat(response.headers).containsEntry("Cache-Control", "no-cache");
        assertThat(body)
                .contains("packfile-uris\n")
                .contains(fixture.publishedPack().packChecksum()
                        + " https://git.example/r/team/project/objects/pack/"
                        + fixture.publishedPack().packId()
                        + ".pack\n");
    }

    @Test
    void postRejectsUnexpectedContentTypeBeforeReadingRepository()
            throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        provider.create(REPOSITORY_NAME).valueOrFailure("repository");
        OrionGitRoute route = new OrionGitRoute(provider, autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        route.handle(
                request(
                        "POST",
                        "/r/team/project.git/git-upload-pack",
                        "application/octet-stream",
                        null,
                        Map.of(
                                "Host", "git.example",
                                "Git-Protocol", "version=2"),
                        fetchRequest(GitObjectId.of("1".repeat(40))),
                        repositorySecurityContext()),
                response.proxy(),
                null);

        assertThat(response.status)
                .isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    }

    private FileNativeGitRepositoryProvider provider() {
        return new FileNativeGitRepositoryProvider(tempDir);
    }

    private static GitTransportConfig autoPackfileUriConfig() {
        GitTransportConfig config = new GitTransportConfig();
        config.getPackfileUri().setBaseUri("auto");
        return config;
    }

    private static PublishedObjectFixture publishObject(
            FileNativeGitRepositoryProvider provider) {
        NativeGitRepository repository = provider.create(REPOSITORY_NAME)
                .valueOrFailure("repository");
        byte[] data = "published".getBytes(StandardCharsets.UTF_8);
        GitObjectId objectId = repository.writeObject(ObjectType.BLOB, data);
        byte[] packBytes = pack(objectId, data);
        PackIngestionResult result =
                accept(repository.beginPackIngestion(LIMITS), packBytes);
        PackIngestionResult.Complete complete =
                (PackIngestionResult.Complete) result;
        repository.updateRef(
                "refs/heads/main",
                NULL_ID,
                objectId.value());
        return new PublishedObjectFixture(
                objectId,
                complete.publishedPack().orElseThrow());
    }

    private static byte[] pack(
            GitObjectId objectId,
            byte[] data) {
        LooseObjectStore sourceObjects = new LooseObjectStore();
        GitObjectId stored = sourceObjects.write(ObjectType.BLOB, data);
        assertThat(stored).isEqualTo(objectId);
        return produce(new NoDeltaPackBuilder().producer(
                sourceObjects,
                List.of(objectId)));
    }

    private static PackIngestionResult accept(
            PackIngestionSession session,
            byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            PackIngestionResult result = session.accept(input);
            if (result instanceof PackIngestionResult.NeedInput) {
                return session.endOfInput();
            }
            return result;
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

    private static byte[] fetchRequest(GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePacket(output, "command=fetch\n");
        output.writeBytes("0001".getBytes(StandardCharsets.US_ASCII));
        writePacket(output, "packfile-uris https\n");
        writePacket(output, "want " + objectId.value() + "\n");
        writePacket(output, "done\n");
        output.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static void writePacket(
            ByteArrayOutputStream output,
            String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
        output.writeBytes("%04x".formatted(payloadBytes.length + 4)
                .getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(payloadBytes);
    }

    private static HttpServletRequest request(
            String method,
            String pathInfo,
            String contentType,
            String service,
            Map<String, String> headers,
            byte[] body,
            SecurityContext securityContext) {
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) ->
                switch (invokedMethod.getName()) {
                    case "getMethod" -> method;
                    case "getPathInfo" -> pathInfo;
                    case "getRequestURI" -> pathInfo;
                    case "getContextPath" -> "";
                    case "getParameter" ->
                            "service".equals(args[0]) ? service : null;
                    case "getContentType" -> contentType;
                    case "getInputStream" ->
                            new ByteArrayServletInputStream(body);
                    case "getHeader" -> headers.get((String) args[0]);
                    case "isSecure" -> true;
                    case "getScheme" -> "http";
                    case "getServerName" -> "internal";
                    case "getServerPort" -> 8080;
                    case "getRemoteAddr" -> "127.0.0.1";
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

    private static SecurityContext repositorySecurityContext() {
        AccessControl.Grant grant = new AccessControlDraft.Grant(
                "repository",
                new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, REPOSITORY_NAME)
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

    private record PublishedObjectFixture(
            GitObjectId objectId,
            PublishedPack publishedPack) {
    }

    private static final class ResponseRecorder {
        private int status;
        private String contentType;
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
                        case "getOutputStream" ->
                                new ByteArrayServletOutputStream(body);
                        case "toString" -> "HttpServletResponseRecorder";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(
                                method.toString());
                    });
        }

        private String body() {
            return body.toString(StandardCharsets.US_ASCII);
        }
    }

    private static final class ByteArrayServletInputStream
            extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] data) {
            input = new ByteArrayInputStream(data);
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
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
