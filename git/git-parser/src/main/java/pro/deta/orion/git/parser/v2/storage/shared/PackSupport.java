package pro.deta.orion.git.parser.v2.storage.shared;



public final class PackSupport {
    private PackSupport() {}

    public static void closeUnreturned(Object value, Throwable failure) {
        if (value instanceof AutoCloseable resource) {
            try {
                resource.close();
            } catch (Throwable cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

}
