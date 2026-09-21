package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.util.Arrays;
import java.util.zip.ZipException;
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

    @Test
    void preservesSourceFailuresDuringHeaderAndPayloadReads() throws Exception {
        for (IOException failure : new IOException[]{new IOException("connection reset"),
                new EOFException("source closed"), new ZipException("source failure")}) {
            for (int limit : new int[]{0, 10}) {
                InputStream source = failingSource(gzip(REQUEST), limit, failure);
                assertThatThrownBy(() -> {
                    try (InputStream decoded = GitHttpRequestBody.decode(source, "gzip")) {
                        decoded.readAllBytes();
                    }
                }).isSameAs(failure);
            }
        }
    }

    @Test
    void preservesSourceFailureBetweenConcatenatedMembers() throws Exception {
        byte[] first = gzip(REQUEST);
        byte[] both = new byte[first.length * 2];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(first, 0, both, first.length, first.length);
        IOException failure = new IOException("connection reset between members");
        try (InputStream decoded = GitHttpRequestBody.decode(
                failingSource(both, first.length + 1, failure), "gzip")) {
            assertThatThrownBy(decoded::readAllBytes).isSameAs(failure);
        }
    }

    @Test
    void preservesSourceCloseFailure() throws Exception {
        IOException failure = new IOException("close failed");
        InputStream source = new ByteArrayInputStream(gzip(REQUEST)) {
            @Override
            public void close() throws IOException {
                throw failure;
            }
        };
        InputStream decoded = GitHttpRequestBody.decode(source, "gzip");
        assertThat(decoded.readAllBytes()).isEqualTo(REQUEST);
        assertThatThrownBy(decoded::close).isSameAs(failure);
    }

    @Test
    void rejectsTruncatedPayloadWhileSkipping() throws Exception {
        byte[] compressed = gzip(REQUEST);
        try (InputStream decoded = GitHttpRequestBody.decode(
                new ByteArrayInputStream(Arrays.copyOf(compressed, compressed.length - 5)), "gzip")) {
            assertThatThrownBy(() -> decoded.skip(REQUEST.length + 1))
                    .isInstanceOf(InvalidContentEncodingException.class);
        }
    }

    @Test
    void preservesPayloadFailureForSingleByteReadsAndSkip() throws Exception {
        IOException failure = new IOException("payload read failed");
        try (InputStream decoded = GitHttpRequestBody.decode(failingSource(gzip(REQUEST), 10, failure), "gzip")) {
            assertThatThrownBy(decoded::read).isSameAs(failure);
        }
        try (InputStream decoded = GitHttpRequestBody.decode(failingSource(gzip(REQUEST), 10, failure), "gzip")) {
            assertThatThrownBy(() -> decoded.skip(1)).isSameAs(failure);
        }
    }

    @Test
    void preservesSourceAvailabilityFailureAfterMember() throws Exception {
        IOException failure = new IOException("availability failed");
        InputStream source = new java.io.FilterInputStream(new ByteArrayInputStream(gzip(REQUEST))) {
            @Override
            public int available() throws IOException {
                throw failure;
            }
        };
        try (InputStream decoded = GitHttpRequestBody.decode(source, "gzip")) {
            assertThatThrownBy(decoded::readAllBytes).isSameAs(failure);
        }
    }

    @Test
    void decodesConcatenatedMembers() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(gzip(REQUEST));
        encoded.write(gzip(REQUEST));
        try (InputStream decoded = GitHttpRequestBody.decode(
                new ByteArrayInputStream(encoded.toByteArray()), "gzip")) {
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write(REQUEST);
            expected.write(REQUEST);
            assertThat(decoded.readAllBytes()).isEqualTo(expected.toByteArray());
        }
    }

    private static InputStream failingSource(byte[] bytes, int limit, IOException failure) {
        return new InputStream() {
            private int position;

            @Override
            public int read() throws IOException {
                if (position >= limit) {
                    throw failure;
                }
                return position < bytes.length ? bytes[position++] & 0xff : -1;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                int value = read();
                if (value >= 0) {
                    target[offset] = (byte) value;
                    return 1;
                }
                return -1;
            }

            @Override
            public int available() {
                return bytes.length - position;
            }
        };
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body);
        }
        return output.toByteArray();
    }
}
