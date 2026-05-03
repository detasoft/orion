package pro.deta.orion.internal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.config.WorkDir;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.ServerKeyService;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.event.type.VolatileUserAdded;
import pro.deta.orion.internal.jgit.OrionClientSshdSessionFactoryProvider;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
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
public class GitInternalStorage implements OrionApplicationStageEventListener {
    public static final int GIT_INTERNAL_STORAGE_PRIORITY = 7;
    private final Path storageArea;
    private final ServerKeyService serverKeyService;
    private final OrionProvider orionProvider;
    private final OrionConfiguration config;
    private final List<GitAccessParams> storageAreas = new ArrayList<>();
    private final OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider;

    @Inject
    public GitInternalStorage(@WorkDir Path workDir, OrionConfiguration config, ServerKeyService serverKeyService, OrionProvider orionProvider, OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider) {
        this.storageArea = workDir.resolve("storage-area");
        this.serverKeyService = serverKeyService;
        this.config = config;
        this.orionProvider = orionProvider;
        this.orionClientSshdSessionFactoryProvider = orionClientSshdSessionFactoryProvider;
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.INIT, this::onInit);
        registrar.register(ApplicationState.STARTING, this::onStart).priority(GIT_INTERNAL_STORAGE_PRIORITY).waitForCompletion(); // after GIT_TRANSPORT_PRIORITY leads to finish all transport started first
    }

    private OrionStageCallResult onInit() {
        FileUtils.mkdirs(storageArea);
        // registers event handler to propagate changes in git to areas
        orionProvider.getEventManager().registerTypeHandler(GitReceiveOrionEvent.class, (event) -> {
            for (GitReceiveOrionEvent.GitReceiveEventRef ref : event.getReceiveEventRefs()) {
                for(GitAccessParams area : storageAreas) {
                    if (isSameRepository(event, area)) {
                        if (ref.getRefName().endsWith(area.getBranch())) {
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
                serverKeyService.getPublicKeys(),
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
