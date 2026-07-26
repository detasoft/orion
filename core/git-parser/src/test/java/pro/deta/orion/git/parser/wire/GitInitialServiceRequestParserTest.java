package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static ByteBuf requestBuffer(String payload) {
        ByteBuf buffer = Unpooled.buffer();
        writePktLine(buffer, payload);
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
