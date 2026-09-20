package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;

@FunctionalInterface
public interface GitPackRead<R> {
    R read(long size, BufferedByteInputV2 source) throws IOException;
}
