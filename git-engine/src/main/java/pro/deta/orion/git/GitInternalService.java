package pro.deta.orion.git;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.util.io.TeeInputStream;
import org.eclipse.jgit.util.io.TeeOutputStream;
import org.slf4j.helpers.MessageFormatter;
import pro.deta.orion.git.ssh.SshCommandFactory;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static pro.deta.orion.auth.check.PermissionChecks.*;

@AllArgsConstructor
@Slf4j
public class GitInternalService {
    public static final int TIMEOUT = 5;
    private final GitRepositoryProvider gitRepositorySupplier;

    public void service(String clientId, InputStream inputStream, OutputStream outputStream, SshCommandFactory.BAOSTeeOutputStream errorStream, String requestId, Function<InputStream, GitCommand> cmdResolved) {
        Thread.currentThread().setName(MessageFormatter.format("Serving {} []", clientId, requestId).getMessage());
        GitCommand gitCommand = null;

        try {
            gitCommand = cmdResolved.apply(inputStream);
            String repositoryName = gitCommand.getRepositoryName();
            Thread.currentThread().setName(MessageFormatter.format("Serving {} [{}] ({})", clientId, new Object[] { requestId, gitCommand}).getMessage());
            if (!gitRepositorySupplier.exists(repositoryName) && gitCommand.isRead())
                    return;
            try {

                Repository r = null;
                if (gitCommand.isWrite() && !gitRepositorySupplier.exists(repositoryName)) {
                    permissionChecker().ALLOW_TO_CREATE_REPO.assertThat(gitCommand.getRepositoryName());
                    r = gitRepositorySupplier.findOrCreate(repositoryName);
                } else {
                    r = gitRepositorySupplier.find(repositoryName);
                }

                Set<String> extraParameters = gitCommand.getProperties().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.toSet());
                switch (gitCommand.getCommand()) {
                    case UPLOAD:
                        UploadPack up = new UploadPack(r);
                        up.setTimeout(TIMEOUT);
                        up.setExtraParameters(extraParameters);
                        up.upload(inputStream, outputStream, errorStream);
                        break;
                    case RECEIVE:
                        ReceivePack rp = new ReceivePack(r);
                        rp.setTimeout(TIMEOUT);
                        rp.receive(inputStream, outputStream, errorStream);
                        break;
                    default:
                        PacketLineOut pktOut = new PacketLineOut(outputStream);
                        pktOut.writeString(MessageFormatter.format("ERR {}\n", errorStream.getBranch().toString()).getMessage());
                        pktOut.end();
                }
            } catch (ServiceMayNotContinueException e) {
                PacketLineOut pktOut = new PacketLineOut(outputStream);
                pktOut.writeString("ERR " + e.getMessage() + "\n");
                pktOut.end();
            }
        } catch (IOException e) {
            log.error("Error while serving {}", gitCommand, e);
        } finally {
            closeIt(inputStream);
            flushIt(outputStream);
            flushIt(errorStream);
        }
    }

    private void flushIt(Flushable flushable) {
        try {
            flushable.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeIt(Closeable flushable) {
        try {
            flushable.close();
        } catch (IOException ignored) {
        }
    }

    public static GitCommand parse(InputStream inputStream) {
        String cmd = "";
        try {
            PacketLineIn packetLineIn = new PacketLineIn(inputStream);
            cmd = packetLineIn.readStringRaw();
            return parseGitCommand(cmd, Collections.emptyList());
        } catch (IOException e) {
            throw new IllegalArgumentException("Wrong command or io: " + cmd, e);
        }
    }

    public static GitCommand parseGitCommand(String cmd, List<String> extraProperties) {
        log.debug("Command is : [{}]", cmd);
        String[] parts = cmd.split("\\x00");
        String[] commands = parts[0].split(" ");
        GitCommand.Command command = GitCommand.Command.parseFrom(commands[0]);
        String locator = commands[1];
        locator = locator.replaceAll("[^a-zA-Z0-9\\-_\\.\\/]","");
        GitCommand gcmd = new GitCommand(command, locator);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i]== null || parts[i].isBlank())
                continue;
            parseAndSetProperty(gcmd, parts[i]);
        }
        for (String ep : extraProperties) {
            parseAndSetProperty(gcmd, ep);
        }
        if (command.isUnknown()) {
            log.warn("Received UNKNOWN COMMAND {} [{}}] ", commands[0], gcmd);
        }
        log.debug("Command: [{}]", gcmd);
        return gcmd;
    }

    private static void parseAndSetProperty(GitCommand gcmd, String parts) {
        String[] prop = parts.split("=");
        if (prop.length > 1)
            gcmd.addProperty(prop[0], prop[1]);
        else
            gcmd.addProperty(prop[0], "");
    }
}
