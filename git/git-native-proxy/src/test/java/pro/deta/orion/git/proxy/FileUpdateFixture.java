package pro.deta.orion.git.proxy;

import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.fileapi.GitFile;
import pro.deta.orion.git.fileapi.GitFileAccess;
import pro.deta.orion.git.fileapi.GitFileApi;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Materializes small wire fixtures through the streaming file API for protocol tests. */
public final class FileUpdateFixture {
    private FileUpdateFixture() {}

    public static Prepared prepare(GitFileApi files, String branch, Map<String, GitFile> changes,
                                   Set<String> deleted, String message, GitCommitAuthor author) throws GitOperationException {
        try {
            return files.withAccess(branch, message, author, access -> prepare(access, changes, deleted));
        } catch (GitOperationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GitOperationException("Cannot prepare test pack", failure);
        }
    }

    public static Prepared prepare(GitFileApi files, String branch, String revision, Map<String, GitFile> changes,
                                   Set<String> deleted, String message, GitCommitAuthor author) throws GitOperationException {
        try {
            return files.withAccess(branch, revision, message, author, access -> prepare(access, changes, deleted));
        } catch (GitOperationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GitOperationException("Cannot prepare test pack", failure);
        }
    }

    private static Prepared prepare(GitFileAccess access, Map<String, GitFile> changes, Set<String> deleted)
            throws Exception {
        for (String path : deleted) {
            access.delete(path);
        }
        for (Map.Entry<String, GitFile> entry : changes.entrySet()) {
            byte[] content = entry.getValue().content();
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(content))) {
                access.write(entry.getKey(), entry.getValue().mode(), content.length, input);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        access.writePack(new OutputStreamBufferedByteOutput(output));
        return new Prepared(output.toByteArray(), access.refUpdates());
    }

    public record Prepared(byte[] pack, List<RefUpdate> refUpdates) {
        @Override
        public byte[] pack() {
            return pack.clone();
        }
    }
}
