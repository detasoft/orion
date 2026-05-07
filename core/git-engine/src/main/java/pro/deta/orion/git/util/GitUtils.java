package pro.deta.orion.git.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.transport.PacketLineOut;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.stream.IOEStreamProvider;

import java.io.IOException;
import java.io.OutputStream;

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

    public static void runUploadToClient(GitRepository repository, GitUploadRequest request, IOEStreamProvider streams) throws IOException {
        try {
            repository.upload(
                    request,
                    streams.getInputStream(),
                    streams.getOutputStream(),
                    streams.getErrorStream());
        } catch (Exception e) {
            log.error("Git upload failed for {}", repository.description(), e);
            writeProtocolError(streams.getOutputStream(), e.getMessage());
        }
    }

    public static void runReceiveFromClient(GitRepository repository, GitReceiveRequest request, IOEStreamProvider streams) throws IOException {
        try {
            repository.receive(
                    request,
                    streams.getInputStream(),
                    streams.getOutputStream(),
                    streams.getErrorStream());
        } catch (Exception e) {
            log.error("Git receive failed for {}", repository.description(), e);
            writeProtocolError(streams.getOutputStream(), e.getMessage());
        }
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

    public static int gitProtocolTimeoutSeconds() {
        return switch (OrionUtils.JVM_MODE) {
            case DEFAULT -> GIT_PROTOCOL_TIMEOUT_SECONDS;
            case JVM_DEBUG -> 0;
        };
    }
}
