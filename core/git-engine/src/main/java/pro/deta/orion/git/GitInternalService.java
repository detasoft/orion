package pro.deta.orion.git;

import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.pack.PackStatistics;
import org.eclipse.jgit.transport.*;
import org.slf4j.helpers.MessageFormatter;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.data.FetchRepositorySecurityCheck;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.event.type.GitUploadOrionEvent;
import pro.deta.orion.git.ssh.SshCommandFactory;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.Result.Failure;
import pro.deta.orion.util.stream.IOEStreamProvider;
import pro.deta.orion.util.stream.TeeOutputStream;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static pro.deta.orion.auth.check.PermissionChecks.*;
import static pro.deta.orion.git.util.GitUtils.writeErrorIntoOS;

@Slf4j
public class GitInternalService {
    private final GitRepositoryProvider repositoryProvider;
    private final OrionProvider orionProvider;

    @Inject
    public GitInternalService(GitRepositoryProvider repositoryProvider, OrionProvider orionProvider) {
        this.repositoryProvider = repositoryProvider;
        this.orionProvider = orionProvider;
    }

    public void service(String clientId, IOEStreamProvider streams, String requestId, Function<InputStream, GitCommand> cmdResolved) {
        OrionUtils.wrapRunnableWithThreadName(MessageFormatter.format("Serving {} []", clientId, requestId).getMessage(), () -> {
            GitCommand gitCommand = null;

            try {
                gitCommand = cmdResolved.apply(streams.getInputStream());
                String repositoryName = gitCommand.getRepositoryName();
                Thread.currentThread().setName(MessageFormatter.format("Serving {} [{}] ({})", clientId, new Object[]{requestId, gitCommand}).getMessage());
                if (!repositoryProvider.exists(repositoryName) && gitCommand.isRead())
                    return;
                try {

                    Result<Repository> r = null;
                    if (gitCommand.isWrite() && !repositoryProvider.exists(repositoryName)) {
                        permissionChecker().ALLOW_TO_CREATE_REPO.assertThat(gitCommand.getRepositoryName());
                        r = repositoryProvider.findOrCreate(repositoryName);
                    } else {
                        r = repositoryProvider.find(repositoryName);
                    }

                    Repository r1 = switch (r) {
                        case Result.Success<Repository>(var success) -> success;
                        case Failure<Repository>(var code, var message, var throwable) ->
                                throw new IllegalStateException("Unexpected value: " + code + " / " + message, throwable);
                        default -> throw new IllegalStateException("Unexpected value: " + r);
                    };

                    Set<String> extraParameters = gitCommand.getProperties().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.toSet());
                    switch (gitCommand.getCommand()) {
                        case UPLOAD:
                            UploadPack uploadPack = GitUtils.uploadPack(r1, extraParameters);
                            attachHooks(uploadPack, repositoryName);
                            GitUtils.upload(uploadPack, streams);
                            break;
                        case RECEIVE:
                            ReceivePack receivePack = GitUtils.receivePack(r1);
                            attachHooks(receivePack, repositoryName);
                            GitUtils.receive(receivePack, streams);
                            break;
                        default:
                            writeErrorIntoOS(streams.getOutputStream(), "unknown command");
                            break;
                    }
                } catch (ServiceMayNotContinueException e) {
                    writeErrorIntoOS(streams.getOutputStream(), e.getMessage());
                }
            } catch (SecurityException e) {
                log.error("ACCESS_DENIED {} / {}", gitCommand, e.getMessage());
            } catch (Exception e) {
                log.error("Error while serving {}", gitCommand, e);
            }
        });
    }

    private void attachHooks(ReceivePack rp, String repositoryName) {
        rp.setPostReceiveHook(new PostReceiveHook() {
            @Override
            public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
                orionProvider.getEventManager().publish(() -> {
                    GitReceiveOrionEvent event = new GitReceiveOrionEvent(repositoryName, getSc().getUserIdentity().getUserId());
                    for (ReceiveCommand sc : commands) {
                        event.addReceiveEventRef(sc.getRefName(), sc.getOldId(), sc.getNewId(), sc.getType(), sc.getResult());
                    }
                    return event;
                });
            }
        });
    }

    private void attachHooks(UploadPack up, String repositoryName) {
        up.setPostUploadHook(new PostUploadHook() {
            @Override
            public void onPostUpload(PackStatistics stats) {
                orionProvider.getEventManager().publish(new GitUploadOrionEvent(repositoryName, stats));
            }
        });
        up.setPreUploadHook(new PreUploadHook() {
            @Override
            public void onBeginNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntOffered) throws ServiceMayNotContinueException {
                try {
                    permissionChecker().ALLOW_READ_ACCESS.assertThat(repositoryName);
                    try (Git git = new Git(up.getRepository())) {
                        permissionChecker().ALLOW_TO_FETCH_REPO.assertThat(new FetchRepositorySecurityCheck(git, up.getRepository(), wants, repositoryName));
                    }
                } catch (OrionSecurityException e) {
                    throw new ServiceMayNotContinueException(e);
                }
            }

            @Override
            public void onEndNegotiateRound(UploadPack up, Collection<? extends ObjectId> wants, int cntCommon, int cntNotFound, boolean ready) throws ServiceMayNotContinueException {
                log.trace("onEndNegotiateRound");
            }

            @Override
            public void onSendPack(UploadPack up, Collection<? extends ObjectId> wants, Collection<? extends ObjectId> haves) throws ServiceMayNotContinueException {
                log.trace("onSendPack " + wants + " " + haves);
            }
        });
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
