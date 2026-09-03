package pro.deta.orion.git.proxy;

final class BootstrapGitProxyException extends IllegalStateException {
    BootstrapGitProxyException(String stage) {
        super("Remote Git bootstrap failed during " + stage);
    }
}
