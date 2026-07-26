package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitInitialServiceRequestParserTest {
    @Test
    void parsesUploadPackRequestAndLeavesFollowingBufferBytesUnread() {
        ByteBuf input = requestBuffer("git-upload-pack /project.git\0host=git.example.com=9418\0version=2\0");
        input.writeCharSequence("next-phase", StandardCharsets.US_ASCII);

        try {
            GitInitialServiceRequest request = GitInitialServiceRequestParser.read(input);

            assertThat(request.service()).isEqualTo(GitInitialServiceRequest.Service.UPLOAD_PACK);
            assertThat(request.repositoryPath()).isEqualTo("/project.git");
            assertThat(request.parameters())
                    .containsEntry("host", "git.example.com=9418")
                    .containsEntry("version", "2");
            assertThat(input.readCharSequence(input.readableBytes(), StandardCharsets.US_ASCII).toString())
                    .isEqualTo("next-phase");
        } finally {
            input.release();
        }
    }

    @Test
    void parsesReceivePackRequestWithEmptyFinalField() {
        ByteBuf input = requestBuffer("git-receive-pack /team/project.git\0host=git.example.com\0\0");

        try {
            GitInitialServiceRequest request = GitInitialServiceRequestParser.read(input);

            assertThat(request.service()).isEqualTo(GitInitialServiceRequest.Service.RECEIVE_PACK);
            assertThat(request.repositoryPath()).isEqualTo("/team/project.git");
            assertThat(request.parameters()).containsEntry("host", "git.example.com");
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsTruncatedHeaderAsWireError() {
        ByteBuf input = ascii("003");

        try {
            assertThatThrownBy(() -> GitInitialServiceRequestParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INCOMPLETE_HEADER,
                                    GitWireError.Phase.CONTROL_HEADER,
                                    0,
                                    0,
                                    "Incomplete initial service request header")));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsUnsupportedServiceAsWireError() {
        ByteBuf input = requestBuffer("git-archive /project.git\0");

        try {
            assertThatThrownBy(() -> GitInitialServiceRequestParser.read(input))
                    .isInstanceOfSatisfying(GitWireException.class, error -> assertThat(error.error())
                            .isEqualTo(new GitWireError(
                                    GitWireError.Kind.INVALID_INITIAL_SERVICE_REQUEST,
                                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                                    0,
                                    4,
                                    "Unsupported Git service: git-archive")));
        } finally {
            input.release();
        }
    }

    private static ByteBuf requestBuffer(String payload) {
        ByteBuf buffer = Unpooled.buffer();
        writePktLine(buffer, payload);
        return buffer;
    }

    private static ByteBuf ascii(String value) {
        ByteBuf buffer = Unpooled.buffer(value.length());
        buffer.writeCharSequence(value, StandardCharsets.US_ASCII);
        return buffer;
    }

    private static void writePktLine(ByteBuf buffer, String payload) {
        int headerIndex = buffer.writerIndex();
        buffer.writeZero(4);
        int payloadLength = buffer.writeCharSequence(payload, StandardCharsets.UTF_8);
        String packetLength = "%04x".formatted(payloadLength + 4);
        for (int i = 0; i < packetLength.length(); i++) {
            buffer.setByte(headerIndex + i, packetLength.charAt(i));
        }
    }
}
