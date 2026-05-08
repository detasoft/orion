package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.RequestToAclUpdate;
import pro.deta.orion.git.storage.GitAccessParams;
import pro.deta.orion.git.storage.GitBackedInternalStorage;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.OrionEnableServiceSupport;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.Result;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Slf4j
@Singleton
@ToString
public class GitAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage, OrionApplicationStageEventListener {
    private final GitBackedInternalStorage gitBackedInternalStorage;
    private final OrionExecutor orionExecutor;
    private final OrionEventManager orionEventManager;
    private final OrionConfiguration.AccessControlConfig accessControl;
    private GitAccessParams area;
    private Path orionConfigFileName;

    @Inject
    public GitAccessControlStorage(GitBackedInternalStorage gitBackedInternalStorage, OrionConfiguration orionConfiguration, OrionExecutor orionExecutor, OrionEventManager orionEventManager) {
        this.gitBackedInternalStorage = gitBackedInternalStorage;
        this.accessControl = orionConfiguration.getAccessControl();
        this.orionExecutor = orionExecutor;
        this.orionEventManager = orionEventManager;
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.INIT, this::onInit).priority(OrionAccessControlServiceImpl.INIT_PRIORITY+1);
    }

    public OrionStageCallResult onInit() {
        OrionStageCallResult result = OrionStageCallResult.defaultWithWait(); // need to wait until event published to a eventManager
        area = gitBackedInternalStorage.registerArea(result,"acl", accessControl.getUrl(), accessControl.getUsername(), accessControl.getCredential(), accessControl.getBranch(), (event) -> {
            orionEventManager.publish(new RequestToAclUpdate(event.toString()));
        });
        orionConfigFileName = area.getLocalPath().resolve(accessControl.getSettingsFileName());
        return result;
    }

    @Override
    public Result<AccessControlSnapshot> load() {
        try {
            area.updateLocalCopy();
            File f = getOrionConfigFileNameFile();
            if (f.exists()) {
                return new Result.Success<>(AccessControlSnapshot.singleFile(
                        accessControl.getSettingsFileName(),
                        Files.readAllBytes(f.toPath())));
            }
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        } catch (Exception e) {
            log.error("Error while updating area {}: {}", area.getCheckoutDir(), e.getMessage());
            return new Result.Failure<>(Result.FailureCode.GENERAL, e.getMessage());
        }
    }

    private File getOrionConfigFileNameFile() {
        return orionConfigFileName.toFile();
    }


    @Override
    public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
        List<Path> writtenFiles = new ArrayList<>();
        try {
            for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
                Path file = area.getLocalPath().resolve(entry.getKey()).normalize();
                if (!file.startsWith(area.getLocalPath())) {
                    throw new IllegalArgumentException("ACL file escapes checkout directory: " + entry.getKey());
                }
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.write(file, entry.getValue());
                writtenFiles.add(file);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot write ACL snapshot", e);
        }
        area.commitFiles(request.message(), request.author(), writtenFiles);
    }

    @Override
    public String primaryPath() {
        return accessControl.getSettingsFileName();
    }
}
