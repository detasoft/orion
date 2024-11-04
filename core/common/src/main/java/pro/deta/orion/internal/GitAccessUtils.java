package pro.deta.orion.internal;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.*;

import java.util.List;

@Slf4j
public class GitAccessUtils {

    public static void addRemoteIfNotExists(Git git, String originName, URIish originUrl) {
        try {
            List<RemoteConfig> remotes = git.remoteList().call();
            boolean remoteFound = false;
            for (RemoteConfig rc : remotes) {
                if (rc.getName().equals(originName)) {
                    remoteFound = true;
                }
            }
            if (remoteFound) {
                // just to make sure
                git.remoteSetUrl().setRemoteName(originName).setRemoteUri(originUrl).call();
            } else {
                git.remoteAdd().setName(originName).setUri(originUrl).call();
            }
        } catch (Exception e) {
            log.error("Error while listing remotes", e);
        }
    }

}
