package pro.deta.orion.git.storage;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.*;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.git.storage.auth.Auth;
import pro.deta.orion.git.storage.jgit.JGitAuth;
import pro.deta.orion.git.storage.jgit.OrionClientSshdSessionFactoryProvider;
import pro.deta.orion.internal.CheckedConsumer;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.Result;

import java.net.URI;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static pro.deta.orion.acl.schema.AccessControl.TRUE_STRING;
import static pro.deta.orion.util.FileUtils.wipeDirectory;

@RequiredArgsConstructor
@ToString
@Getter
@Slf4j
public class GitAccessParams {
    public static final String ORIGIN_NAME = "origin";
    public static final String DEFAULT_BRANCH_NAME = "master";

    private final Path checkoutDir;
    private final URI uri;
    private final Auth auth;
    private final String branch;
    private Consumer<GitReceiveOrionEvent> eventConsumer;
    private final AtomicReference<Integer> localPortAtomicReference = new AtomicReference<>();
    private final AtomicReference<AreaState> areaStateAtomicReference = new AtomicReference<>(AreaState.OFF);
    private final OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider;

    public GitAccessParams(Path checkoutDir, OrionConfiguration.AppTransport appTransport,
                           URI initialUri,
                           String username,
                           String credential,
                           String branchName,
                           Consumer<GitReceiveOrionEvent> eventConsumer,
                           OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider
                           ) {
        this.checkoutDir = checkoutDir.normalize().toAbsolutePath();
        this.branch = branchName;
        this.eventConsumer = eventConsumer;
        this.orionClientSshdSessionFactoryProvider = orionClientSshdSessionFactoryProvider;
        switch (initialUri.getScheme()) {
            case "local" -> {
                String repositoryName = ResourceLocation.from(initialUri).normalizedRelativePath();
                uri = GitAccessScheme.GIT.format(null, appTransport.getGit(), repositoryName);
                auth = new Auth.LocalNoneAuth(repositoryName);
            }
            case "ssh" -> {
                uri = initialUri;
                auth = new Auth.SshAuthKey(username, Path.of(Objects.toString(credential, "")));
            }
            case "http", "https" -> {
                uri = initialUri;
                auth = new Auth.HttpAuth(username, Objects.toString(credential, ""));
            }
            default -> throw new IllegalStateException("Scheme not supported: " + initialUri.getScheme());
        }
        areaStateAtomicReference.set(AreaState.CONFIGURED);
    }

    public Path getLocalPath() {
        return checkoutDir;
    }

    public void onUpdate(GitReceiveOrionEvent event) {
        eventConsumer.accept(event);
    }

    public String getOriginUrl() {
        return uri.toString();
    }

    public CloneCommand getCloneCommand() {
        return injectAuthentication(() -> Git.cloneRepository()
                .setURI(getOriginUrl())
                .setDirectory(checkoutDir.toFile())
                .setBranch(getBranch())
        );
    }

    public boolean assignUserGrants(AccessControl.User user) {
        AccessControl.Grant grant = user.addGrant("TMP")
                .addKey(AccessControl.GrantKey.CREATE, TRUE_STRING)
                .addKey(AccessControl.GrantKey.WRITE, TRUE_STRING);

        switch (auth) {
            case Auth.HttpAuth httpAuth -> {
            }
            case Auth.LocalNoneAuth noneAuth -> {
                grant.addKey(AccessControl.GrantKey.REPOSITORY, noneAuth.localRepositoryName());
                user.addGrant("LOCAL_NETWORK_ACCESS").addKey(AccessControl.GrantKey.NETWORK_SOURCE, "127.0.0.1");
                return true;
            }
            case Auth.SshAuthKey(String username, Path path, Optional<KeyPair> kp) -> { // not possible to authorize via
                user.addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, KeyUtils.publicKeyToString(kp.get().getPublic()));
            }
            case Auth.SshAuthKeyPair(String username, Optional<KeyPair> keyPair) -> {
                user.addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, KeyUtils.publicKeyToString(keyPair.get().getPublic()));
            }
        }
        return false;
    }


    private PullCommand pullCommand(Git git) {
        PullCommand pullCommand = injectAuthentication(git::pull);
        return pullCommand.setRemote(ORIGIN_NAME).setRemoteBranchName(getBranch());
    }

    public Git openGit() {
        try {
            return Git.open(checkoutDir.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open git repository in " + checkoutDir + " from " + uri + " auth: " + auth, e);
        }
    }

    public PushCommand pushCommand(Git git) {
        return injectAuthentication(git::push);
    }

    public Result<Void> onStart() {
        areaStateAtomicReference.set(AreaState.STARTING);
        wipeDirectory(checkoutDir);
        try {
            Git.init().setDirectory(checkoutDir.toFile()).call().close();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        callInGit((git) -> {
            GitAccessUtils.addRemoteIfNotExists(git, ORIGIN_NAME, new URIish(getOriginUrl()));
            checkoutIfPossible(git, getBranch());
        });
        Result<Void> result = callInGit((git) -> {
            if (false) {
                getCloneCommand().call().close();
            } else {
                PullCommand pullCommand = injectAuthentication(git::pull).setRemoteBranchName(getBranch()).setRemote(ORIGIN_NAME);
                try {
                    pullCommand.call();
                } catch (RefNotAdvertisedException ignored) {
                } // empty repo, we will commit and push
            }
        });
        if (result.isFailure()) {
            return markAreaAsFailed(result);
        }
        areaStateAtomicReference.set(AreaState.STARTED);
        return new Result.Success<>(null);
    }

    private Result<Void> markAreaAsFailed(Result<Void> result) {
        log.warn("Marking area as failed: {}", result);
        areaStateAtomicReference.set(AreaState.FAILED);
        return result;
    }

    public <V extends TransportCommand<?, ?>> V injectAuthentication(Supplier<V> gitCommandSupplier) {
        V gitCommand = gitCommandSupplier.get();
        JGitAuth.injectIntoGitCommand(auth, gitCommand, orionClientSshdSessionFactoryProvider);
        return gitCommand;
    }


    private static boolean checkoutIfPossible(Git git, String branchName) throws GitAPIException {
        List<Ref> refs = git.branchList().call();
        for (Ref ref: refs) {
            if (ref.getName().equalsIgnoreCase(branchName)) {
                git.checkout().setName(branchName).call();
                return true;
            }
        }
        return false;
    }

//    public <V> Result<V> callInGit(CheckedFunction<Git, V> callable) {
//        try (Git git = openGit()) {
//            return new Result.Success<>(callable.apply(git));
//        } catch (Exception e) {
//            return Result.Failure.generalFailure("Can't call git function", e);
//        }
//    }

    public Result<Void> callInGit(CheckedConsumer<Git> callable) {
        try (Git git = openGit()) {
            callable.accept(git);
            return new Result.Success<>(null);
        } catch (Exception e) {
            return Result.Failure.generalFailure("Can't call git function " + e.getMessage() + "in " + checkoutDir + " for " + getOriginUrl() + " / " + getBranch(), e);
        }
    }

    public void push() {
        callInGit((git) -> {
            pushCommand(git).call();
        });
    }


    public void commitFile(String message, UserEmail author, Path fileName) {
        commitFiles(message, author, List.of(fileName));
    }

    public void commitFiles(String message, UserEmail author, Collection<Path> fileNames) {
        List<Path> relativeToRoot = new java.util.ArrayList<>();
        for (Path fileName : fileNames) {
            relativeToRoot.add(getCheckoutDir().relativize(fileName));
        }
        callInGit((git) -> {
            addAndCommit(git, message, author, relativeToRoot.toArray(Path[]::new));
        });
    }

    private void addAndCommit(Git git, String message, UserEmail author, Path... paths) throws GitAPIException {
        AddCommand addCommand = git.add();
        for (Path p : paths) {
            addCommand.addFilepattern(p.toString());
        }
        addCommand.call();
        CommitCommand cm = git.commit();
        cm.setAuthor(author.getUsername(), author.getEmail() != null ? author.getEmail() : "");
        cm.setMessage(message).call();
        push();
    }


    public void updateLocalCopy() {
        callInGit((git) -> {
            try {
                pullCommand(git).call();
            } catch (RefNotAdvertisedException ignored) {
            }
        });
    }
}
