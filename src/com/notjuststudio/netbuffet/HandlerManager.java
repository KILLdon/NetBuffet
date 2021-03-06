package com.notjuststudio.netbuffet;

import com.notjuststudio.bytebun.ByteBun;
import com.notjuststudio.fpnt.FPNTContainer;
import com.notjuststudio.fpnt.FPNTDecoder;
import com.notjuststudio.secretingredient.Recipe;
import com.notjuststudio.util.ByteBunReader;
import com.notjuststudio.util.ByteBunUtils;
import com.sun.istack.internal.NotNull;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

class HandlerManager extends ChannelInboundHandlerAdapter {

    protected final Connection CONNECTION;
    private final boolean PRINT_EXCEPTIONS;

    HandlerManager(@NotNull final Connection connection, @NotNull final boolean exceptions) {
        this.CONNECTION = connection;
        this.PRINT_EXCEPTIONS = exceptions;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (CONNECTION.secureBase.cryptoProtective.get()) {
            if (CONNECTION.secureBase instanceof Server) {
                final Server server = (Server) CONNECTION.secureBase;
                final byte[] publicKey = server.keyPair.getPublic().getEncoded();
                final ByteBuf key = Unpooled.buffer(publicKey.length);
                key.writeBytes(publicKey);
                ctx.writeAndFlush(key);
            }
        } else {
            CONNECTION.wasInitialized.set(true);
            if (CONNECTION.active != null)
                CONNECTION.active.handle(CONNECTION);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (CONNECTION.inactive != null)
            CONNECTION.inactive.handle(CONNECTION);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final ByteBun input = ByteBunUtils.createBun((ByteBuf) msg);

        if (!CONNECTION.wasInitialized.get()) {
            if (CONNECTION.secureBase instanceof Server) {
                final Server server = (Server) CONNECTION.secureBase;
                final byte[] cipher = new byte[input.writerIndex()];
                input.readBytes(cipher);
                final byte[] key = Recipe.decryptRSA(server.keyPair.getPrivate(), cipher);
                CONNECTION.secretKey = Recipe.createAESKey(key);

                if (CONNECTION.active != null)
                    CONNECTION.active.handle(CONNECTION);
            } else {
                final Client client = (Client) CONNECTION.secureBase;
                CONNECTION.secretKey = Recipe.generateAESKey();
                final byte[] publicKey = new byte[input.writerIndex()];
                input.readBytes(publicKey);

                final byte[] key = Recipe.encryptRSA(Recipe.createRSAPublicKey(publicKey), CONNECTION.secretKey.getEncoded());
                final ByteBuf answer = Unpooled.buffer(key.length);
                answer.writeBytes(key);
                ctx.writeAndFlush(answer);

                client.statusReady();

                if (CONNECTION.active != null)
                    CONNECTION.active.handle(CONNECTION);
            }

            CONNECTION.wasInitialized.set(true);
            return;
        }

        final ByteBun message;

        if (CONNECTION.secureBase.cryptoProtective.get()) {
            final byte[] source = new byte[input.writerIndex()];
            input.readBytes(source);
            final byte[] result = Recipe.decryptAES(CONNECTION.secretKey, source);
            message = ByteBun.allocate(result.length);
            message.writeBytes(result);
        } else {
            message = input;
        }

        if (!message.readBoolean()) {
            final String key = ByteBunUtils.readString(message);
            if (CONNECTION.handlers.containsKey(key)) {
                final HandlerContainer handler = CONNECTION.handlers.get(key);
                final FPNTContainer container = new FPNTContainer(CONNECTION.expanders());
                FPNTDecoder.decode(new ByteBunReader(message), container);
                handler.handle(CONNECTION, container);
            }
        } else {
            final String name = ByteBunUtils.readString(message);
            if (!CONNECTION.synchronizedContainers.containsKey(name)) {
                CONNECTION.createContainer(name);
            }
            final FPNTContainer container = CONNECTION.synchronizedContainers.get(name);
            final byte type = message.readByte();
            final String key = ByteBunUtils.readString(message);
            if (message.readerIndex() == message.capacity()) {
                container.remove(type, key);
            } else {
                FPNTDecoder.decode(new ByteBunReader(message), container, type, key);
            }
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (PRINT_EXCEPTIONS)
            cause.printStackTrace();
        if (CONNECTION.exception != null)
            CONNECTION.exception.handle(CONNECTION, cause);
        ctx.close();
    }
}
