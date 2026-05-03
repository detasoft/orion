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
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.event.type.GitUploadOrionEvent;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.Result.Failure;
import pro.deta.orion.util.stream.IOEStreamProvider;

import java.io.*;
import java.util.*;
import java.util.function.Function;

import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static pro.deta.orion.auth.check.PermissionChecks.*;
import static pro.deta.orion.git.util.GitUtils.writeProtocolError;

@Slf4j
public class GitInternalService {
    private final GitRepositoryProvider repositoryProvider;
    private final OrionEventManager eventManager;

    @Inject
    public GitInternalService(GitRepositoryProvider repositoryProvider, OrionEventManager eventManager) {
        this.repositoryProvider = repositoryProvider;
        this.eventManager = eventManager;
    }

    public void service(String clientId, IOEStreamProvider streams, String requestId, Function<InputStream, GitCommand> cmdResolved) {
        OrionUtils.wrapRunnableWithThreadName(MessageFormatter.format("Serving {} []", clientId, requestId).getMessage(), () -> {
            GitCommand gitCommand = null;
            try {
                gitCommand = cmdResolved.apply(streams.getInputStream());
                Thread.currentThread().setName(MessageFormatter.format("Serving {} [{}] ({})", clientId, new Object[]{requestId, gitCommand}).getMessage());
                serveCommand(gitCommand, streams);
            } catch (ServiceMayNotContinueException e) {
                writeProtocolError(streams.getOutputStream(), e.getMessage());
            } catch (OrionSecurityException e) {
                log.error("ACCESS_DENIED {} / {}", gitCommand, e.getMessage());
            } catch (SecurityException e) {
                log.error("ACCESS_DENIED {} / {}", gitCommand, e.getMessage());
            } catch (Exception e) {
                log.error("Error while serving {}", gitCommand, e);
            }
        });
    }

    private void serveCommand(GitCommand gitCommand, IOEStreamProvider streams) throws IOException, ServiceMayNotContinueException, OrionSecurityException {
        String repositoryName = gitCommand.getRepositoryName();
        Optional<Repository> repository = openRepositoryFor(gitCommand, repositoryName);
        if (repository.isEmpty()) {
            return;
        }

        switch (gitCommand.getCommand()) {
            case UPLOAD -> serveUploadPackToClient(gitCommand, repository.get(), repositoryName, streams);
            case RECEIVE -> serveReceivePackFromClient(repository.get(), repositoryName, streams);
            default -> writeProtocolError(streams.getOutputStream(), "unknown command");
        }
    }

    private Optional<Repository> openRepositoryFor(GitCommand gitCommand, String repositoryName) throws OrionSecurityException {
        boolean repositoryExists = repositoryProvider.exists(repositoryName);
        if (!repositoryExists && gitCommand.isRead()) {
            return Optional.empty();
        }

        Result<Repository> repositoryResult;
        if (gitCommand.isWrite() && !repositoryExists) {
            permissionChecker().ALLOW_TO_CREATE_REPO.assertThat(repositoryName);
            repositoryResult = repositoryProvider.findOrCreate(repositoryName);
        } else {
            if (gitCommand.isWrite()) {
                permissionChecker().ALLOW_WRITE_ACCESS.assertThat(repositoryName);
            }
            repositoryResult = repositoryProvider.find(repositoryName);
        }
        return Optional.of(repositoryFrom(repositoryResult));
    }

    private static Repository repositoryFrom(Result<Repository> result) {
        return switch (result) {
            case Result.Success<Repository>(var repository) -> repository;
            case Failure<Repository>(var code, var message, var throwable) ->
                    throw new IllegalStateException("Unexpected value: " + code + " / " + message, throwable);
            default -> throw new IllegalStateException("Unexpected value: " + result);
        };
    }

    private void serveUploadPackToClient(GitCommand gitCommand, Repository repository, String repositoryName, IOEStreamProvider streams) throws IOException {
        UploadPack uploadPack = GitUtils.createUploadPackToClient(repository, extraParameters(gitCommand));
        attachHooks(uploadPack, repositoryName);
        GitUtils.runUploadPackToClient(uploadPack, streams);
    }

    private void serveReceivePackFromClient(Repository repository, String repositoryName, IOEStreamProvider streams) throws IOException {
        ReceivePack receivePack = GitUtils.createReceivePackFromClient(repository);
        attachHooks(receivePack, repositoryName);
        GitUtils.runReceivePackFromClient(receivePack, streams);
    }

    private static Set<String> extraParameters(GitCommand gitCommand) {
        Set<String> extraParameters = new LinkedHashSet<>();
        for (Map.Entry<Object, Object> entry : gitCommand.getProperties().entrySet()) {
            extraParameters.add(entry.getKey() + "=" + entry.getValue());
        }
        return extraParameters;
    }

    private void attachHooks(ReceivePack rp, String repositoryName) {
        rp.setPostReceiveHook(new PostReceiveHook() {
            @Override
            public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
                eventManager.publish(() -> {
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
                eventManager.publish(new GitUploadOrionEvent(repositoryName, stats));
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
        if (cmd == null || cmd.isBlank()) {
            throw new IllegalArgumentException("Malformed git command: empty command");
        }
        String[] parts = cmd.split("\\x00", -1);
        String commandPart = parts.length > 0 ? parts[0].trim() : "";
        String[] commands = commandPart.split("\\s+");
        if (commands.length < 2 || commands[0].isBlank() || commands[1].isBlank()) {
            throw new IllegalArgumentException("Malformed git command: " + commandPart);
        }
        GitCommand.Command command = GitCommand.Command.parseFrom(commands[0]);
        String locator = commands[1];
        locator = locator.replaceAll("[^a-zA-Z0-9\\-_\\.\\/]","");
        if (locator.isBlank()) {
            throw new IllegalArgumentException("Malformed git command: empty repository locator");
        }
        GitCommand gcmd = new GitCommand(command, locator);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i]== null || parts[i].isBlank())
                continue;
            parseAndSetProperty(gcmd, parts[i]);
        }
        if (extraProperties != null) {
            for (String ep : extraProperties) {
                parseAndSetProperty(gcmd, ep);
            }
        }
        if (command.isUnknown()) {
            log.warn("Received UNKNOWN COMMAND {} [{}}] ", commands[0], gcmd);
        }
        log.debug("Command: [{}]", gcmd);
        return gcmd;
    }

    private static void parseAndSetProperty(GitCommand gcmd, String parts) {
        if (parts == null || parts.isBlank()) {
            return;
        }
        int separator = parts.indexOf('=');
        if (separator < 0) {
            gcmd.addProperty(parts, "");
            return;
        }
        String name = parts.substring(0, separator);
        if (name.isBlank()) {
            return;
        }
        gcmd.addProperty(name, parts.substring(separator + 1));
    }
}
