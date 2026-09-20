package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.util.Result;

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

import static org.assertj.core.api.Assertions.assertThat;

class OrionGitPackfileRouteTest {

    @TempDir
    private Path tempDir;

    @Test
    void hidesInternalPackEvenFromReaderWithRepositoryGrant() throws Exception {
        FileNativeGitRepositoryProvider backend = new FileNativeGitRepositoryProvider(tempDir);
        NativeGitRepository repository = backend.create("team/project").valueOrFailure("repository");
        PublishedPackFixture pack = publishPack(repository);
        RecordingProvider provider = new RecordingProvider(backend);
        provider.publicName = false;
        ResponseRecorder response = new ResponseRecorder();

        service(new OrionGitPackfileRoute(provider), request("GET",
                "/r/team/project.git/objects/pack/" + pack.packId().toHex() + ".pack",
                repositorySecurityContext("team/project")), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.body.size()).isZero();
        assertThat(provider.readCalls).isZero();
    }

    @Test
    void usesTheSameCanonicalNameForAuthorizationAndProviderRead() throws Exception {
        FileNativeGitRepositoryProvider backend =
                new FileNativeGitRepositoryProvider(tempDir);
        NativeGitRepository repository = backend.create("team/project")
                .valueOrFailure("repository");
        RecordingProvider provider = new RecordingProvider(backend);
        PublishedPackFixture pack = publishPack(repository);
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(provider);
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "GET",
                        "/r/team%2Fproject.git/objects/pack/"
                                + pack.packId().toHex()
                                + ".pack",
                        repositorySecurityContext("team/project")),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType)
                .isEqualTo(OrionGitPackfileRoute.PACK_CONTENT_TYPE);
        assertThat(response.headers).containsEntry("Cache-Control", "no-cache");
        assertThat(response.contentLength).isEqualTo(pack.packBytes().length);
        assertThat(response.body.toByteArray()).isEqualTo(pack.packBytes());
        assertThat(provider.readCalls).isEqualTo(1);
        assertThat(provider.lastReadName).isEqualTo("team/project");
    }

    @Test
    void rejectsInvalidPackIdentifier() throws Exception {
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(
                new FileNativeGitRepositoryProvider(tempDir));
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "GET",
                        "/r/team/project.git/objects/pack/not-hex.pack",
                        repositorySecurityContext("team/project")),
                response.proxy());

        assertThat(response.status)
                .isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    void returnsNotFoundForMissingPack() throws Exception {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(tempDir);
        provider.create("team/project").valueOrFailure("repository");
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(provider);
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "GET",
                        "/r/team/project.git/objects/pack/"
                                + "a".repeat(40)
                                + ".pack",
                        repositorySecurityContext("team/project")),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void rejectsReaderWithoutRepositoryGrant() throws Exception {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(tempDir);
        NativeGitRepository repository = provider.create("team/project")
                .valueOrFailure("repository");
        PublishedPackFixture pack = publishPack(repository);
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(provider);
        ResponseRecorder response = new ResponseRecorder();

        service(route,
                request(
                        "GET",
                        "/r/team/project.git/objects/pack/"
                                + pack.packId().toHex()
                                + ".pack",
                        authenticatedContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void rejectsInvalidRepositoryNamesBeforeProviderRead() throws Exception {
        RecordingProvider provider = new RecordingProvider(
                new FileNativeGitRepositoryProvider(tempDir));
        OrionGitPackfileRoute route = new OrionGitPackfileRoute(provider);
        String packId = "a".repeat(40);

        for (String repositoryPath : List.of(
                "team/../project.git", "Repo.git", "repo%GG.git", "//repo.git")) {
            ResponseRecorder response = new ResponseRecorder();

            service(route,
                    request(
                            "GET",
                            "/r/" + repositoryPath + "/objects/pack/" + packId + ".pack",
                            repositorySecurityContext("repo")),
                    response.proxy());

            assertThat(response.status)
                    .as("repository path %s", repositoryPath)
                    .isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        }

        assertThat(provider.readCalls).isZero();
        assertThat(provider.lastReadName).isNull();
    }

    private static PublishedPackFixture publishPack(
            NativeGitRepository repository) throws IOException {
        byte[] data = "published".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1);
             BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(data))) {
            writer.writeObject(GitObjectType.BLOB, data.length, input);
            writer.finish();
        }
        byte[] packBytes = bytes.toByteArray();
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(packBytes))) {
            IndexedPack pack = repository.ingest(input);
            return new PublishedPackFixture(repository.storage().persist(pack), packBytes);
        }
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

    private static SecurityContext authenticatedContext() {
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("git-user", List.of()));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                handler));
    }

    private record PublishedPackFixture(
            PackId packId,
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
        private String lastReadName;
        private boolean publicName = true;

        @Override
        public boolean isPublicRepositoryName(String repositoryName) {
            return publicName;
        }

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
            lastReadName = repositoryName;
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
