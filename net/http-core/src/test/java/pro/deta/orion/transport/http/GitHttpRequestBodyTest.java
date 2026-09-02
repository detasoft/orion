package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHttpRequestBodyTest {
    private static final byte[] REQUEST = "0009done\n0000".getBytes(StandardCharsets.US_ASCII);

    @Test
    void leavesIdentityBodyUnchanged() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(REQUEST);

        assertThat(GitHttpRequestBody.decode(input, null)).isSameAs(input);
        assertThat(GitHttpRequestBody.decode(input, " ")).isSameAs(input);
        assertThat(GitHttpRequestBody.decode(input, " identity ")).isSameAs(input);
    }

    @Test
    void decodesGzipBodyWithoutBufferingTheWholeRequest() throws Exception {
        try (InputStream decoded = GitHttpRequestBody.decode(
                new ByteArrayInputStream(gzip(REQUEST)), "GZip")) {
            assertThat(decoded.readAllBytes()).isEqualTo(REQUEST);
        }
    }

    @Test
    void rejectsUnsupportedEncoding() {
        assertThatThrownBy(() -> GitHttpRequestBody.decode(
                new ByteArrayInputStream(REQUEST), "br"))
                .isInstanceOf(UnsupportedContentEncodingException.class);
    }

    @Test
    void rejectsMultipleEncodings() {
        assertThatThrownBy(() -> GitHttpRequestBody.decode(
                new ByteArrayInputStream(REQUEST), "gzip, identity"))
                .isInstanceOf(UnsupportedContentEncodingException.class);
    }

    @Test
    void classifiesTruncatedGzipHeaderAsInvalidEncoding() {
        assertThatThrownBy(() -> GitHttpRequestBody.decode(
                new ByteArrayInputStream(new byte[]{0x1f, (byte) 0x8b}), "gzip"))
                .isInstanceOf(InvalidContentEncodingException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void classifiesGzipFailureWhileReadingAsInvalidEncoding() throws Exception {
        byte[] compressed = gzip(REQUEST);
        compressed[compressed.length - 1] ^= 1;

        try (InputStream decoded = GitHttpRequestBody.decode(
                new ByteArrayInputStream(compressed), "gzip")) {
            assertThatThrownBy(decoded::readAllBytes)
                    .isInstanceOf(InvalidContentEncodingException.class)
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }
}
