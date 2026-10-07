package pro.deta.orion.util;

import java.io.IOException;

/** Changes completed by apply or discard. Discard after completion is harmless. */
public interface Modification {
    void apply() throws IOException;

    default void discard() throws IOException {};
}
