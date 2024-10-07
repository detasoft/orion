package pro.deta.orion.settings;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.util.Pair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;

@Slf4j
public class SettingsHolder {
    private Settings _instance = updateSettings(new Settings(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));

    public Settings getInstance() {
        return _instance;
    }

    public Settings updateSettings(Settings settings) {
        Settings result = settings.unmodify();
        return result;
    }

    public Settings generateDefaultSettings(Path orionConfig, String defaultRootPassword) throws NoSuchAlgorithmException, IOException {
        Settings s = new Settings();
        log.warn("DefaultRootPassword: [{}]", defaultRootPassword);
        MessageDigest md = MessageDigest.getInstance("SHA3-256");
        String encodedSha = Base64.getEncoder().encodeToString(md.digest(defaultRootPassword.getBytes(StandardCharsets.UTF_8)));
        s.getUsers().add(new Settings.User("root", "default", "superuser",
                new ArrayList<>() {{ add(new Settings.Credential(Settings.CredentialType.SHA3_256, encodedSha)); }},
                new ArrayList<>() {{ add("ROOT"); }},
                new ArrayList<>()
        ));
        s.getRoles().add(new Settings.Role("ROOT", new ArrayList<>() {{
            add("CONNECT");
            add("ALL_REPOSITORY");
            add("ALL_BRANCH");
        }}));
        s.getGrants().add(new Settings.Grant("CONNECT", Settings.LevelKey.NETWORK, new HashMap<>() {{
            put(Settings.GrantKey.NETWORK_SOURCE, "*");
        }}));
        s.getGrants().add(new Settings.Grant("ALL_REPOSITORY", Settings.LevelKey.REPOSITORY, new HashMap<>() {{
            put(Settings.GrantKey.REPOSITORY, "*");
            put(Settings.GrantKey.READ_WRITE_CREATE, "7");
        }}));
        s.addGrant("ALL_BRANCH", Settings.LevelKey.BRANCH, Pair.of(Settings.GrantKey.REPOSITORY, "*"), Pair.of(Settings.GrantKey.BRANCH, "*"), Pair.of(Settings.GrantKey.FORCE, "true"));
        return updateSettings(s);
    }
}