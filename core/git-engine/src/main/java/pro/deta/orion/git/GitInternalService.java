package pro.deta.orion.git;

import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PacketLineIn;
import org.slf4j.helpers.MessageFormatter;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.git.auth.GitFetchAccessRules;
import pro.deta.orion.git.auth.GitFetchResource;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.event.type.GitUploadOrionEvent;
import pro.deta.orion.git.common.GitReceiveRequest;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitUploadRequest;
import pro.deta.orion.git.util.GitUtils;
import pro.deta.orion.util.OrionUtils;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.Result.Failure;
import pro.deta.orion.util.stream.IOEStreamProvider;

import java.io.*;
import java.util.*;
import java.util.function.Function;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;
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

    public void service(SecurityContext securityContext, String clientId, IOEStreamProvider streams, String requestId, Function<InputStream, GitCommand> cmdResolved) {
        Objects.requireNonNull(securityContext, "securityContext");
        OrionUtils.wrapRunnableWithThreadName(MessageFormatter.format("Serving {} []", clientId, requestId).getMessage(), () -> {
            GitCommand gitCommand = null;
            try {
                gitCommand = cmdResolved.apply(streams.getInputStream());
                Thread.currentThread().setName(MessageFormatter.format("Serving {} [{}] ({})", clientId, new Object[]{requestId, gitCommand}).getMessage());
                serveCommand(securityContext, gitCommand, streams);
            } catch (OrionSecurityException | SecurityException e) {
                log.error("ACCESS_DENIED {} / {}", gitCommand, e.getMessage());
                writeProtocolError(streams.getOutputStream(), "ACCESS_DENIED");
            } catch (Exception e) {
                log.error("Error while serving {}", gitCommand, e);
            }
        });
    }

    private void serveCommand(SecurityContext securityContext, GitCommand gitCommand, IOEStreamProvider streams) throws IOException, OrionSecurityException {
        if (gitCommand.getCommand().isUnknown()) {
            writeProtocolError(streams.getOutputStream(), "unknown command");
            return;
        }

        String repositoryName = gitCommand.getRepositoryName();
        Optional<GitRepository> repositoryResult = openRepositoryFor(securityContext, gitCommand, repositoryName);
        if (repositoryResult.isEmpty()) {
            return;
        }

        try (GitRepository repository = repositoryResult.get()) {
            switch (gitCommand.getCommand()) {
                case UPLOAD -> serveUploadPackToClient(securityContext, gitCommand, repository, repositoryName, streams);
                case RECEIVE -> serveReceivePackFromClient(securityContext, repository, repositoryName, streams);
                default -> writeProtocolError(streams.getOutputStream(), "unknown command");
            }
        }
    }

    private Optional<GitRepository> openRepositoryFor(SecurityContext securityContext, GitCommand gitCommand, String repositoryName) throws OrionSecurityException {
        boolean repositoryExists = repositoryProvider.exists(repositoryName);
        if (!repositoryExists && gitCommand.isRead()) {
            return Optional.empty();
        }

        RepositoryResource repositoryResource = RepositoryResource.of(repositoryName);
        Result<GitRepository> repositoryResult;
        if (gitCommand.isWrite() && !repositoryExists) {
            accessEnforcer().require(securityContext, repositoryResource, RepositoryAccessRules.create());
            repositoryResult = repositoryProvider.findOrCreate(repositoryName);
        } else {
            if (gitCommand.isRead()) {
                accessEnforcer().require(securityContext, repositoryResource, RepositoryAccessRules.read());
            }
            if (gitCommand.isWrite()) {
                accessEnforcer().require(securityContext, repositoryResource, RepositoryAccessRules.write());
            }
            repositoryResult = repositoryProvider.find(repositoryName);
        }
        return Optional.of(repositoryFrom(repositoryResult));
    }

    private static GitRepository repositoryFrom(Result<GitRepository> result) {
        return switch (result) {
            case Result.Success<GitRepository>(var repository) -> repository;
            case Failure<GitRepository>(var code, var message, var throwable) ->
                    throw new IllegalStateException("Unexpected value: " + code + " / " + message, throwable);
            default -> throw new IllegalStateException("Unexpected value: " + result);
        };
    }

    private void serveUploadPackToClient(SecurityContext securityContext, GitCommand gitCommand, GitRepository repository, String repositoryName, IOEStreamProvider streams) throws IOException {
        GitUploadRequest request = new GitUploadRequest(
                GitUtils.gitProtocolTimeoutSeconds(),
                extraParameters(gitCommand),
                stats -> eventManager.publish(new GitUploadOrionEvent(repositoryName, stats)));
        GitRepository accessCheckedRepository = repository.withFetchAccessCheck(fetchRequest -> {
            try {
                accessEnforcer().require(securityContext, GitFetchResource.of(fetchRequest), GitFetchAccessRules.everyWantedObjectAllowed());
            } catch (OrionSecurityException e) {
                throw new SecurityException("ACCESS_DENIED", e);
            }
        });
        GitUtils.runUploadToClient(accessCheckedRepository, request, streams);
    }

    private void serveReceivePackFromClient(SecurityContext securityContext, GitRepository repository, String repositoryName, IOEStreamProvider streams) throws IOException {
        GitReceiveRequest request = new GitReceiveRequest(
                GitUtils.gitProtocolTimeoutSeconds(),
                refUpdates -> eventManager.publish(() -> {
                    GitReceiveOrionEvent event = new GitReceiveOrionEvent(repositoryName, securityContext.getUserIdentity().getUserId());
                    refUpdates.forEach(event::addReceiveEventRef);
                    return event;
                }));
        GitUtils.runReceiveFromClient(repository, request, streams);
    }

    private static Set<String> extraParameters(GitCommand gitCommand) {
        Set<String> extraParameters = new LinkedHashSet<>();
        for (Map.Entry<Object, Object> entry : gitCommand.getProperties().entrySet()) {
            extraParameters.add(entry.getKey() + "=" + entry.getValue());
        }
        return extraParameters;
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
