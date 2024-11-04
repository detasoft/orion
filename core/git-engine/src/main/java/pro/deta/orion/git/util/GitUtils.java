package pro.deta.orion.git.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.git.OrionJGitSystemReader;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.stream.IOEStreamProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

@Slf4j
public class GitUtils {
    public static final int TIMEOUT = 5;

    public static void printDiff(DiffEntry diff) {
        System.out.printf("Diff: %-6s: %s%6s -> %6s: %s%n",
                diff.getChangeType(),
                diff.getDiffAttribute() != null ? diff.getDiffAttribute() + "-" : "",
                diff.getOldMode(), diff.getNewMode(),
                diff.getOldPath().equals(diff.getNewPath()) ? diff.getNewPath() : diff.getOldPath() + " -> " + diff.getNewPath());
    }

    public static UploadPack uploadPack(Repository repository, Set<String> extraParameters) {
        UploadPack up = new UploadPack(repository);
        up.setTimeout(switch (OrionUtils.JVM_MODE) {
            case DEFAULT -> TIMEOUT;
            case JVM_DEBUG -> 0;
        });
        up.setExtraParameters(extraParameters);
        return up;
    }

    public static ReceivePack receivePack(Repository r1) {
        ReceivePack rp = new ReceivePack(r1);
        rp.setTimeout(switch (OrionUtils.JVM_MODE) {
            case DEFAULT -> TIMEOUT;
            case JVM_DEBUG -> 0;
        });
        return rp;
    }

    public static void upload(UploadPack uploadPack, IOEStreamProvider streams) throws IOException {
        try {
            uploadPack.uploadWithExceptionPropagation(streams.getInputStream(), streams.getOutputStream(), streams.getErrorStream());
        } catch (Exception e) {
            log.error("Exception on {}", uploadPack.getRepository().getDirectory(), e);
            writeErrorIntoOS(streams.getOutputStream(), e.getMessage());
        }
    }

    public static void receive(ReceivePack receivePack, IOEStreamProvider streams) throws IOException {
        receivePack.receive(streams.getInputStream(), streams.getOutputStream(), streams.getErrorStream());
    }

    public static void writeErrorIntoOS(OutputStream os, String message) {
        try {
            PacketLineOut pktOut = new PacketLineOut(os);
            pktOut.writeString("ERR " + message);
            pktOut.end();
        } catch (Exception e) {
            log.error("Error while error writing.", e);
        }
    }

    public static void gitConfigure(SystemReader systemReader) {
        SystemReader.setInstance(systemReader);
    }
}
