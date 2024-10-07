package pro.deta.orion.rbac;

import lombok.RequiredArgsConstructor;
import pro.deta.orion.config.AppConfigContext;
import pro.deta.orion.settings.SettingsHolder;

@RequiredArgsConstructor
public class RBACService {
    private final AppConfigContext.AppConfiguration appConfiguration;
    private final SettingsHolder settingsHolder;
}
