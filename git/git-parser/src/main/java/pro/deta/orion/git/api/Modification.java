package pro.deta.orion.git.api;

import java.io.IOException;

/** Changes completed by apply or discard. Discard after completion is harmless. */
public interface Modification {
    void apply() throws IOException;

    void discard() throws IOException;
}
