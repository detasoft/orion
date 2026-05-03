package pro.deta.orion.git.s3;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.PackParser;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class S3ObjectInserter extends ObjectInserter {
    private final S3ObjectDatabase s3Database;

    public S3ObjectInserter(S3ObjectDatabase s3Database) {
        this.s3Database = s3Database;
    }

    @Override
    public ObjectId insert(int objectType, long length, InputStream in) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public PackParser newPackParser(InputStream in) throws IOException {
        return new S3PackParser(s3Database, in, s3Database.getClient());
    }

    @Override
    public ObjectReader newReader() {
        return null;
    }

    @Override
    public void flush() throws IOException {

    }

    @Override
    public void close() {
    }
}
