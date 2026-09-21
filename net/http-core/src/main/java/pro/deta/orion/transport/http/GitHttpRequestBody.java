package pro.deta.orion.transport.http;

import java.io.FilterInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

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
        private ErrorClassifyingGzipInputStream(InputStream input) throws IOException {
            super(open(input));
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (SourceFailure error) {
                throw (IOException) error.getCause();
            } catch (EOFException | ZipException error) {
                throw new InvalidContentEncodingException(error);
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                return in.read(buffer, offset, length);
            } catch (SourceFailure error) {
                throw (IOException) error.getCause();
            } catch (EOFException | ZipException error) {
                throw new InvalidContentEncodingException(error);
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                return in.skip(count);
            } catch (SourceFailure error) {
                throw (IOException) error.getCause();
            } catch (EOFException | ZipException error) {
                throw new InvalidContentEncodingException(error);
            }
        }

        private static InputStream open(InputStream input) throws IOException {
            try {
                return new GZIPInputStream(new SourceInputStream(input));
            } catch (SourceFailure error) {
                throw (IOException) error.getCause();
            } catch (EOFException | ZipException error) {
                throw new InvalidContentEncodingException(error);
            }
        }

    }

    /**
     * Marks source I/O separately from decoder errors. An unchecked marker also prevents the JDK
     * gzip decoder from swallowing source failures while probing a concatenated member's header.
     * The outer decoder wrapper restores the original IOException before returning to its caller.
     */
    private static final class SourceInputStream extends FilterInputStream {
        private SourceInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (IOException error) {
                throw new SourceFailure(error);
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                return in.read(bytes, offset, length);
            } catch (IOException error) {
                throw new SourceFailure(error);
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return in.available();
            } catch (IOException error) {
                throw new SourceFailure(error);
            }
        }
    }

    private static final class SourceFailure extends RuntimeException {
        private SourceFailure(IOException cause) {
            super(cause);
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
