package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWireBootstrapTest {

    @Test
    void readsNativeGitDaemonInitialRequest() throws Exception {
        GitWireBootstrap bootstrap = nativeDaemon(
                "0038git-upload-pack /repo.git\0host=localhost\0\0version=2\0");

        InitialRequestData data = bootstrap.data();

        assertThat(bootstrap.wire()).isNotNull();
        assertThat(data.getService()).isEqualTo(InitialRequestService.UPLOAD_PACK);
        assertThat(data.getRepositoryPath()).isEqualTo("repo");
        assertThat(data.getHost()).isEqualTo("localhost");
        assertThat(data.getProtocolVersion())
                .contains(InitialRequestData.ProtocolVersion.V2);
    }

    @Test
    void rejectsNativeGitDaemonRequestWithoutMetadataSeparator() {
        assertThatThrownBy(() -> nativeDaemon("001agit-upload-pack /repo.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed native Git request");
    }

    @Test
    void createsSmartHttpBootstrap() {
        GitWireBootstrap bootstrap = GitWireBootstrap.smartHttp(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(new byte[0])),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()),
                InitialRequestService.RECEIVE_PACK,
                "/team/project.git",
                "localhost",
                "version=2:agent=ignored");

        assertThat(bootstrap.wire()).isNotNull();
        assertThat(bootstrap.data().getService())
                .isEqualTo(InitialRequestService.RECEIVE_PACK);
        assertThat(bootstrap.data().getRepositoryPath()).isEqualTo("team/project");
        assertThat(bootstrap.data().getHost()).isEqualTo("localhost");
        assertThat(bootstrap.data().getParameters()).isEqualTo(Map.of("version", "2"));
    }

    @Test
    void createsSshCommandBootstrapData() {
        InitialRequestData data = GitWireBootstrap.sshCommandData(
                "git-upload-pack '/team/project.git'",
                "version=2");

        assertThat(data.getService()).isEqualTo(InitialRequestService.UPLOAD_PACK);
        assertThat(data.getRepositoryPath()).isEqualTo("team/project");
        assertThat(data.getHost()).isNull();
        assertThat(data.getProtocolVersion())
                .contains(InitialRequestData.ProtocolVersion.V2);
    }

    private static GitWireBootstrap nativeDaemon(String ascii) throws Exception {
        return GitWireBootstrap.nativeDaemon(
                new InputStreamBufferedByteInput(
                        new ByteArrayInputStream(
                                ascii.getBytes(StandardCharsets.US_ASCII))),
                new OutputStreamBufferedByteOutput(new ByteArrayOutputStream()));
    }
}
