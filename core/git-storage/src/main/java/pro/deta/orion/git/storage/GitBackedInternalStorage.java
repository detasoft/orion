package pro.deta.orion.git.storage;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.git.common.GitRefUpdate;
import pro.deta.orion.event.type.VolatileUserAdded;
import pro.deta.orion.git.storage.jgit.OrionClientSshdSessionFactoryProvider;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.FileUtils;
import pro.deta.orion.util.OrionProvider;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


@Singleton
@Getter
@Slf4j
public class GitBackedInternalStorage implements OrionApplicationStageEventListener {
    private final Path storageArea;
    private final OrionProvider orionProvider;
    private final OrionConfiguration config;
    private final List<GitAccessParams> storageAreas = new ArrayList<>();
    private final OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider;

    @Inject
    public GitBackedInternalStorage(ConfigurationContext configurationContext, OrionConfiguration config, OrionProvider orionProvider, OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider) {
        this(configurationContext.getWorkDir(), config, orionProvider, orionClientSshdSessionFactoryProvider);
    }

    GitBackedInternalStorage(Path workDir, OrionConfiguration config, OrionProvider orionProvider, OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider) {
        this.storageArea = workDir.resolve("storage-area");
        this.config = config;
        this.orionProvider = orionProvider;
        this.orionClientSshdSessionFactoryProvider = orionClientSshdSessionFactoryProvider;
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        task(registrar, ApplicationState.INIT, OrionLifecycleTasks.GIT_BACKED_INTERNAL_STORAGE_INIT, this::onInit)
                .after(OrionLifecycleTasks.ACL_INIT);
        task(registrar, ApplicationState.STARTING, OrionLifecycleTasks.REPOSITORY_STORAGE, this::onStart);
    }

    private OrionStageCallResult onInit() {
        FileUtils.mkdirs(storageArea);
        // registers event handler to propagate changes in git to areas
        orionProvider.getEventManager().registerTypeHandler(GitReceiveOrionEvent.class, (event) -> {
            for (GitRefUpdate ref : event.getReceiveEventRefs()) {
                for(GitAccessParams area : storageAreas) {
                    if (isSameRepository(event, area)) {
                        if (ref.refName().endsWith(area.getBranch())) {
                            area.onUpdate(event);
                            break;
                        }
                    }
                }
            }
        });
        return null;
    }

    private boolean isSameRepository(GitReceiveOrionEvent event, GitAccessParams area) {
        return area.getAuth().matchesLocalRepository(event.getRepositoryName());
    }

    public GitAccessParams registerArea(OrionStageCallResult result, String name, String location, String username, String credential, String branch, Consumer<GitReceiveOrionEvent> eventConsumer) {
        URI uri = URI.create(location);
        Path checkoutPath = storageArea.resolve(name);
        FileUtils.mkdirs(checkoutPath);
        GitAccessParams area = new GitAccessParams(checkoutPath,
                config.getTransports(),
                uri,
                getValueOrDefault(username, "orion_acl"),
                credential,
                getValueOrDefault(branch, GitAccessParams.DEFAULT_BRANCH_NAME),
                eventConsumer,
                orionClientSshdSessionFactoryProvider);
        storageAreas.add(area);
        result.submit(orionProvider.getOrionExecutor(), () -> {
            AccessControl.User user = ACLUtil.createUser(area.getAuth().getUsername(), area.getAuth().getUsername());
            log.trace("Volatile user created: {}", user);
            if (area.assignUserGrants(user)) {
                log.trace("Volatile user grants assigned: {}", user);
                orionProvider.getEventManager().publish(new VolatileUserAdded(user));
            }
        });
        return area;
    }

    private String getValueOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank())
            value = defaultValue;
        return value;
    }

    public OrionStageCallResult onStart() {
        for (GitAccessParams storageArea : storageAreas) {
            try {
                storageArea.onStart();
            } catch (Exception e) {
                log.warn("Failed to start storage area: {}", storageArea, e);
            }
        }
        return null;
    }
}
