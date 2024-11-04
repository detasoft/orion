package pro.deta.orion.git.s3;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class S3RefDatabase extends RefDatabase {
    private final AbstractClient client;
    private final String refPrefix = "refs/heads/";

    @Override
    public void create() throws IOException {
    }

    @Override
    public void close() {
    }

    @Override
    public boolean isNameConflicting(String name) throws IOException {
        return !getConflictingNames(name).isEmpty();
    }

    @Override
    public RefUpdate newUpdate(String name, boolean detach) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public RefRename newRename(String fromName, String toName) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Ref exactRef(String name) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Ref> getRefs(String prefix) throws IOException {
        return client.listKeys(refPrefix + prefix).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, it -> getRef(it.getKey(), it.getValue())));
    }

    @Override
    public List<Ref> getAdditionalRefs() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Ref peel(Ref ref) throws IOException {
        throw new UnsupportedOperationException();
    }

    private S3Ref getRef(String key, String value) {
        return new S3Ref(key, ObjectId.fromString(value));
    }

    @RequiredArgsConstructor
    @Getter
    class S3Ref implements Ref {
        private final String name;
        private final ObjectId objectId;

        @Override
        public boolean isSymbolic() {
            return false;
        }

        @Override
        public Ref getLeaf() {
            return null;
        }

        @Override
        public Ref getTarget() {
            return null;
        }

        @Override
        public ObjectId getPeeledObjectId() {
            return null;
        }

        @Override
        public boolean isPeeled() {
            return false;
        }

        @Override
        public Storage getStorage() {
            return null;
        }
    }
}
