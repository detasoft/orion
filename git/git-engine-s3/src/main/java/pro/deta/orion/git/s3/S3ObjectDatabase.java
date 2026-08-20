package pro.deta.orion.git.s3;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.transport.PackedObjectInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Slf4j
public class S3ObjectDatabase extends ObjectDatabase {
    @Getter
    private final AbstractClient client;

    @Override
    public ObjectInserter newInserter() {
        return new ObjectInserter() {
            @Override
            public ObjectId insert(int objectType, long length, InputStream in) throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public PackParser newPackParser(InputStream in) throws IOException {
                return new S3PackParser(S3ObjectDatabase.this, in, client) ;
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
        };
    }

    @Override
    public ObjectReader newReader() {
        return new S3ObjectReader();
    }

    @Override
    public void close() {
    }

    @Override
    public long getApproximateObjectCount() {
        return 0;
    }
}
