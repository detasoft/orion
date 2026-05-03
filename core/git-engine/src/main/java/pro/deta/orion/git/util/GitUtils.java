package pro.deta.orion.git.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.stream.IOEStreamProvider;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

@Slf4j
public final class GitUtils {
    public static final int GIT_PROTOCOL_TIMEOUT_SECONDS = 5;

    private GitUtils() {
    }

    public static void printDiff(DiffEntry diff) {
        System.out.printf("Diff: %-6s: %s%6s -> %6s: %s%n",
                diff.getChangeType(),
                diff.getDiffAttribute() != null ? diff.getDiffAttribute() + "-" : "",
                diff.getOldMode(), diff.getNewMode(),
                diff.getOldPath().equals(diff.getNewPath()) ? diff.getNewPath() : diff.getOldPath() + " -> " + diff.getNewPath());
    }

    public static UploadPack createUploadPackToClient(Repository repository, Set<String> extraParameters) {
        UploadPack uploadPack = new UploadPack(repository);
        uploadPack.setTimeout(gitProtocolTimeoutSeconds());
        uploadPack.setExtraParameters(extraParameters);
        return uploadPack;
    }

    public static ReceivePack createReceivePackFromClient(Repository repository) {
        ReceivePack receivePack = new ReceivePack(repository);
        receivePack.setTimeout(gitProtocolTimeoutSeconds());
        return receivePack;
    }

    public static void runUploadPackToClient(UploadPack uploadPack, IOEStreamProvider streams) throws IOException {
        try {
            uploadPack.uploadWithExceptionPropagation(
                    streams.getInputStream(),
                    streams.getOutputStream(),
                    streams.getErrorStream());
        } catch (Exception e) {
            log.error("Git upload-pack failed for {}", repositoryDescription(uploadPack.getRepository()), e);
            writeProtocolError(streams.getOutputStream(), e.getMessage());
        }
    }

    public static void runReceivePackFromClient(ReceivePack receivePack, IOEStreamProvider streams) throws IOException {
        receivePack.receive(
                streams.getInputStream(),
                streams.getOutputStream(),
                streams.getErrorStream());
    }

    public static void writeProtocolError(OutputStream outputStream, String message) {
        try {
            PacketLineOut packetLineOut = new PacketLineOut(outputStream);
            packetLineOut.writeString("ERR " + message);
            packetLineOut.end();
        } catch (Exception e) {
            log.error("Failed to write Git protocol error response", e);
        }
    }

    public static void installJGitSystemReader(SystemReader systemReader) {
        SystemReader.setInstance(systemReader);
    }

    private static int gitProtocolTimeoutSeconds() {
        return switch (OrionUtils.JVM_MODE) {
            case DEFAULT -> GIT_PROTOCOL_TIMEOUT_SECONDS;
            case JVM_DEBUG -> 0;
        };
    }

    private static String repositoryDescription(Repository repository) {
        File directory = repository.getDirectory();
        if (directory != null) {
            return directory.toString();
        }
        return repository.getIdentifier();
    }
}
