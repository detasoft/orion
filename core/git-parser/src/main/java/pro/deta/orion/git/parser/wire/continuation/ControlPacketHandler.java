package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.git.parser.wire.control.ControlState;

@FunctionalInterface
public interface ControlPacketHandler {
    Continuation<ByteBuf> next(ControlState control);
}
