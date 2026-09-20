package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;

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
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OrionGitRouteNativeTest {
    private static final String REPOSITORY_NAME = "team/project";
    private static final String NULL_ID = "0".repeat(40);

    @TempDir
    private Path tempDir;

    @Test
    void protocolV2DiscoveryOmitsServiceAnnouncement() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        publishObject(provider);
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "GET",
                        "/r/team%2Fproject.git/info/refs",
                        null,
                        "git-upload-pack",
                        Map.of(
                                "Host", "git.example",
                                "Git-Protocol", "version=2"),
                        new byte[0],
                        repositorySecurityContext()),
                response.proxy());

        String body = response.body();
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo("application/x-git-upload-pack-advertisement");
        assertNoCacheHeaders(response);
        assertThat(body)
                .doesNotContain("# service=git-upload-pack")
                .startsWith("000eversion 2\n")
                .contains("version 2\n")
                .contains("fetch=")
                .contains("ref-in-want")
                .contains("sideband-all");
    }

    @Test
    void postUsesAutoPackfileUriBaseFromSmartHttpRequest() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        PublishedObjectFixture fixture = publishObject(provider);
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
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
                response.proxy());

        String body = response.body();
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo("application/x-git-upload-pack-result");
        assertNoCacheHeaders(response);
        assertThat(body)
                .contains("packfile-uris\n")
                .contains(fixture.packId().toHex()
                        + " https://git.example/r/team/project/objects/pack/"
                        + fixture.packId().toHex()
                        + ".pack\n");
    }

    @Test
    void postDecodesGzipRequestBody() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        PublishedObjectFixture fixture = publishObject(provider);
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "POST",
                        "/r/team/project.git/git-upload-pack",
                        "application/x-git-upload-pack-request",
                        null,
                        Map.of(
                                "Content-Encoding", "gzip",
                                "Git-Protocol", "version=2"),
                        gzip(fetchRequest(fixture.objectId())),
                        repositorySecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo("application/x-git-upload-pack-result");
        assertNoCacheHeaders(response);
        assertThat(response.body()).contains("packfile-uris\n");
    }

    @Test
    void postDecodesGzipReceivePackRequestBody() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        NativeGitRepository repository = provider.create(REPOSITORY_NAME)
                .valueOrFailure("repository");
        byte[] data = "received".getBytes(StandardCharsets.UTF_8);
        ObjectId objectId = blobId(data);
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "POST",
                        "/r/team/project.git/git-receive-pack",
                        "application/x-git-receive-pack-request",
                        null,
                        Map.of("Content-Encoding", "gzip"),
                        gzip(receiveRequest(objectId, pack(objectId, data))),
                        repositoryWriteSecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo("application/x-git-receive-pack-result");
        assertNoCacheHeaders(response);
        assertThat(response.body())
                .contains("unpack ok\n")
                .contains("ok refs/heads/main\n");
        assertThat(repository.refs())
                .containsEntry("refs/heads/main", objectId.toHex());
    }

    @Test
    void postRejectsUnsupportedContentEncoding() throws Exception {
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider()),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "POST",
                        "/r/team/project.git/git-upload-pack",
                        "application/x-git-upload-pack-request",
                        null,
                        Map.of("Content-Encoding", "br"),
                        new byte[0],
                        repositorySecurityContext()),
                response.proxy());

        assertThat(response.status)
                .isEqualTo(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        assertNoCacheHeaders(response);
    }

    @Test
    void postRejectsMalformedGzipRequestBody() throws Exception {
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider()),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "POST",
                        "/r/team/project.git/git-upload-pack",
                        "application/x-git-upload-pack-request",
                        null,
                        Map.of("Content-Encoding", "gzip"),
                        new byte[]{0x1f, (byte) 0x8b},
                        repositorySecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertNoCacheHeaders(response);
    }

    @Test
    void postRejectsUnexpectedContentTypeBeforeReadingRepository()
            throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        provider.create(REPOSITORY_NAME).valueOrFailure("repository");
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "POST",
                        "/r/team/project.git/git-upload-pack",
                        "application/octet-stream",
                        null,
                        Map.of(
                                "Host", "git.example",
                                "Git-Protocol", "version=2"),
                        fetchRequest(new ObjectId("1".repeat(40))),
                        repositorySecurityContext()),
                response.proxy());

        assertThat(response.status)
                .isEqualTo(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        assertNoCacheHeaders(response);
    }

    @Test
    void postRequiresCanonicalExactContentType() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        provider.create(REPOSITORY_NAME).valueOrFailure("repository");
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider),
                autoPackfileUriConfig());

        for (String contentType : List.of(
                "application/x-git-upload-pack-request; charset=UTF-8",
                "Application/X-Git-Upload-Pack-Request")) {
            ResponseRecorder response = new ResponseRecorder();

            service(route,
                    request(
                            "POST",
                            "/r/team/project.git/git-upload-pack",
                            contentType,
                            null,
                            Map.of("Host", "git.example"),
                            new byte[0],
                            repositorySecurityContext()),
                    response.proxy());

            assertThat(response.status)
                    .isEqualTo(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            assertNoCacheHeaders(response);
        }
    }

    @Test
    void headUsesSmartDiscoveryGetSemantics() throws Exception {
        FileNativeGitRepositoryProvider provider = provider();
        publishObject(provider);
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider),
                autoPackfileUriConfig());
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "HEAD",
                        "/r/team/project.git/info/refs",
                        null,
                        "git-upload-pack",
                        Map.of("Host", "git.example"),
                        new byte[0],
                        repositorySecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo("application/x-git-upload-pack-advertisement");
        assertNoCacheHeaders(response);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void rejectsEndpointSpecificWrongMethodsWithAccurateAllowHeader()
            throws Exception {
        OrionGitRoute route = new OrionGitRoute(
                new DefaultGitNativeRepositoryService(provider()),
                autoPackfileUriConfig());
        ResponseRecorder getRpcResponse = new ResponseRecorder();
        ResponseRecorder postDiscoveryResponse = new ResponseRecorder();

        service(route,
                request(
                        "GET",
                        "/r/team/project.git/git-upload-pack",
                        null,
                        null,
                        Map.of(),
                        new byte[0],
                        repositorySecurityContext()),
                getRpcResponse.proxy());
        service(route,
                request(
                        "POST",
                        "/r/team/project.git/info/refs",
                        null,
                        "git-upload-pack",
                        Map.of(),
                        new byte[0],
                        repositorySecurityContext()),
                postDiscoveryResponse.proxy());

        assertThat(getRpcResponse.status)
                .isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(getRpcResponse.headers).containsEntry("Allow", "POST");
        assertNoCacheHeaders(getRpcResponse);
        assertThat(postDiscoveryResponse.status)
                .isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(postDiscoveryResponse.headers)
                .containsEntry("Allow", "GET, HEAD");
        assertNoCacheHeaders(postDiscoveryResponse);
    }

    private static void assertNoCacheHeaders(ResponseRecorder response) {
        assertThat(response.headers)
                .containsEntry("Expires", "Fri, 01 Jan 1980 00:00:00 GMT")
                .containsEntry("Pragma", "no-cache")
                .containsEntry(
                        "Cache-Control",
                        "no-cache, max-age=0, must-revalidate");
    }

    private static void service(
            OrionHttpRoute route,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(route)),
                new OrionHttpResponseWriter(new ObjectMapper()));
        servlet.service(request, response);
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
            FileNativeGitRepositoryProvider provider) throws IOException {
        NativeGitRepository repository = provider.create(REPOSITORY_NAME)
                .valueOrFailure("repository");
        byte[] data = "published".getBytes(StandardCharsets.UTF_8);
        ObjectId objectId = repository.writeObject(GitObjectType.BLOB, data);
        byte[] packBytes = pack(objectId, data);
        PackId packId;
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(packBytes))) {
            IndexedPack pack = repository.ingest(input);
            packId = repository.storage().persist(pack);
        }
        repository.updateRef(
                "refs/heads/main",
                NULL_ID,
                objectId.toHex());
        return new PublishedObjectFixture(
                objectId,
                packId);
    }

    private static ObjectId blobId(byte[] data) {
        java.security.MessageDigest digest = GitHashAlgorithm.SHA1.newDigest();
        digest.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return new ObjectId(java.util.HexFormat.of().formatHex(digest.digest(data)));
    }

    private static byte[] pack(ObjectId objectId, byte[] data) throws IOException {
        assertThat(blobId(data)).isEqualTo(objectId);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
             BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(data))) {
            writer.writeObject(GitObjectType.BLOB, data.length, input);
            writer.finish();
        }
        return bytes.toByteArray();
    }

    private static byte[] fetchRequest(ObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePacket(output, "command=fetch\n");
        output.writeBytes("0001".getBytes(StandardCharsets.US_ASCII));
        writePacket(output, "packfile-uris https\n");
        writePacket(output, "want " + objectId.toHex() + "\n");
        writePacket(output, "done\n");
        output.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }

    private static byte[] receiveRequest(ObjectId objectId, byte[] pack) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePacket(
                output,
                NULL_ID
                        + " "
                        + objectId.toHex()
                        + " refs/heads/main\0report-status\n");
        output.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(pack);
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

    private static SecurityContext repositoryWriteSecurityContext() {
        AccessControl.Grant grant = new AccessControlDraft.Grant(
                "repository",
                new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, REPOSITORY_NAME)
                .addKey(
                        AccessControl.GrantKey.READ_WRITE,
                        AccessControl.TRUE_STRING)
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
            ObjectId objectId,
            PackId packId) {
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
