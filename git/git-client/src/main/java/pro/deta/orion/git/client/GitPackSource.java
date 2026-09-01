package pro.deta.orion.git.client;

import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;

@FunctionalInterface
public interface GitPackSource {
    void writeTo(BufferedByteOutput output) throws IOException;
}
