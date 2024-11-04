package pro.deta.orion.acl.storage;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.RequestToAclUpdate;
import pro.deta.orion.internal.GitAccessParams;
import pro.deta.orion.internal.GitInternalStorage;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.OrionEnableServiceSupport;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.Result;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;


@Slf4j
@Singleton
@ToString
public class GitAccessControlStorage extends OrionEnableServiceSupport implements AccessControlStorage, OrionApplicationStageEventListener {
    private final XmlService xmlService = new XmlService();
    private final GitInternalStorage gitInternalStorage;
    private final OrionExecutor orionExecutor;
    private final OrionEventManager orionEventManager;
    private final OrionConfiguration.AccessControlConfig accessControl;
    private GitAccessParams area;
    private Path orionConfigFileName;

    @Inject
    public GitAccessControlStorage(GitInternalStorage gitInternalStorage, OrionConfiguration orionConfiguration, OrionExecutor orionExecutor, OrionEventManager orionEventManager) {
        this.gitInternalStorage = gitInternalStorage;
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
        area = gitInternalStorage.registerArea(result,"acl", accessControl.getUrl(), accessControl.getUsername(), accessControl.getCredential(), accessControl.getBranch(), (event) -> {
            orionEventManager.publish(new RequestToAclUpdate(event.toString()));
        });
        orionConfigFileName = area.getLocalPath().resolve(accessControl.getSettingsFileName());
        return result;
    }

    @Override
    public Result<AccessControl> loadAccessControl() {
        try {
            area.updateLocalCopy();
            File f = getOrionConfigFileNameFile();
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    return new Result.Success<>(xmlService.deserialize(fis));
                }
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
    public void saveAccessControl(AccessControl accessControl, String message, UserEmail author) {
        File f = getOrionConfigFileNameFile();
        try (FileOutputStream fis = new FileOutputStream(f)) {
            xmlService.serialize(accessControl, fis);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        area.commitFile(message, author, orionConfigFileName);
    }
}
