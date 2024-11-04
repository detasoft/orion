package pro.deta.orion.comm.v3.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.comm.v3.OrionDTLSAsyncHandler;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

@Slf4j
@RequiredArgsConstructor
public class OrionV3DtlsChannelInboundHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private final OrionDTLSAsyncHandler<InetSocketAddress> dtlsSessionHandler;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        BiConsumer<InetSocketAddress, ByteBuf> packetConsumer = (endpoint, data) -> {
            // data.release(); // release the message
            ctx.writeAndFlush(new DatagramPacket(data, endpoint));
        };
        dtlsSessionHandler.setNetworkConsumer(packetConsumer);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, DatagramPacket datagramPacket) {
        datagramPacket.retain(); // take the message count to ourselves
        InetSocketAddress sender = datagramPacket.sender();
        if (sender.getAddress() == null)
            log.error("sender address is not resolved: {}", sender);
        dtlsSessionHandler.channelRead(sender, datagramPacket.recipient(), datagramPacket.content());
    }
}
