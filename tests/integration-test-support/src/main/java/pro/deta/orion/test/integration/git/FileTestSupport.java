package pro.deta.orion.test.integration.git;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcChangeAuthor;
import pro.deta.orion.git.fileapi.GitFileAccess;
import pro.deta.orion.git.fileapi.GitFileApi;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.internal.CheckedFunction;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Materializes small wire-pack fixtures through the streaming file API without publishing them. */
public final class FileTestSupport {
    private FileTestSupport() {}

    public static Prepared prepared(GitFileApi files, String branch, String message, VcChangeAuthor author,
            CheckedFunction<GitFileAccess, Void> changes) throws Exception {
        return files.withAccess(branch, new VcChangeDescription(author, message), access -> {
            changes.apply(access);
            return prepared(access);
        });
    }

    public static Prepared prepared(GitFileApi files, String branch, String revision,
            String message, VcChangeAuthor author, CheckedFunction<GitFileAccess, Void> changes) throws Exception {
        return files.withAccess(branch, revision,
                new VcChangeDescription(author, message), access -> {
            changes.apply(access);
            return prepared(access);
        });
    }

    private static Prepared prepared(GitFileAccess access) throws IOException {
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
