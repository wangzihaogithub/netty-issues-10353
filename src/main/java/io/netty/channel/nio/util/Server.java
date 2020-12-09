package io.netty.channel.nio.util;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.nio.UseCase1;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

@ChannelHandler.Sharable
public abstract class Server<T> extends SimpleChannelInboundHandler<T>{
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(UseCase1.class);
    private final ServerBootstrap bootstrap = new ServerBootstrap();

    public Server() {
        NioEventLoopGroup parentGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("NIO-Server-Boss"));
        EventLoopGroup childGroup = new NioEventLoopGroup(6,new DefaultThreadFactory("NIO-Server-Worker"));
        bootstrap.group(parentGroup, childGroup)
                .channelFactory(NioServerSocketChannel::new)
                .childHandler(new ChannelInitializer() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        Server.this.initChannel(ch);
                    }
                });
    }

    public void initChannel(Channel channel){

    }

    public ChannelFuture start(int port){
        return bootstrap.bind(port).addListener((ChannelFutureListener) future -> {
            logger.info("startup[{}] at port = {}",
                    future.isSuccess()?"success":"fail",port);
        });
    }

}