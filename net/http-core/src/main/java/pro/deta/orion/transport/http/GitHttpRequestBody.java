package pro.deta.orion.transport.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

final class GitHttpRequestBody {
    private GitHttpRequestBody() {
    }

    static InputStream decode(InputStream input, String contentEncoding) throws IOException {
        if (contentEncoding == null
                || contentEncoding.isBlank()
                || "identity".equalsIgnoreCase(contentEncoding.trim())) {
            return input;
        }
        if (!"gzip".equalsIgnoreCase(contentEncoding.trim())) {
            throw new UnsupportedContentEncodingException();
        }
        return new ErrorClassifyingGzipInputStream(input);
    }

    private static final class ErrorClassifyingGzipInputStream extends FilterInputStream {
        private ErrorClassifyingGzipInputStream(InputStream input) throws InvalidContentEncodingException {
            super(open(input));
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (IOException error) {
                throw invalid(error);
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                return in.read(buffer, offset, length);
            } catch (IOException error) {
                throw invalid(error);
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                return in.skip(count);
            } catch (IOException error) {
                throw invalid(error);
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return in.available();
            } catch (IOException error) {
                throw invalid(error);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } catch (IOException error) {
                throw invalid(error);
            }
        }

        private static InputStream open(InputStream input) throws InvalidContentEncodingException {
            try {
                return new GZIPInputStream(input);
            } catch (IOException error) {
                throw invalid(error);
            }
        }

        private static InvalidContentEncodingException invalid(IOException error) {
            return new InvalidContentEncodingException(error);
        }
    }
}

final class UnsupportedContentEncodingException extends IOException {
    UnsupportedContentEncodingException() {
        super("Unsupported Content-Encoding");
    }
}

final class InvalidContentEncodingException extends IOException {
    InvalidContentEncodingException(IOException cause) {
        super("Invalid gzip request body", cause);
    }
}
