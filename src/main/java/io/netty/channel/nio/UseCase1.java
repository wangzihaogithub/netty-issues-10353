package io.netty.channel.nio;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.OutOfDirectMemoryError;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.Assert;

import java.nio.channels.SelectionKey;

/**
 * channel.flush() after a few channel.write() operations may get processed by the pipeline before the writes,
 * causing the connection to hang without flushing data.
 *
 * case 1 ->
 *  if run at IO, is block. never trigger processSelectedKey() method. the event #OP_WRITE.
 *  causing the connection to hang without flushing data.
 *
 * https://github.com/netty/netty/issues/10353
 */
public class UseCase1 {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(UseCase1.class);

    public static void main(String[] args) {
        ServerBootstrap bootstrap = new ServerBootstrap();

        NioEventLoopGroup parentGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("NIO-Server-Boss"));
        EventLoopGroup childGroup = new NioEventLoopGroup(6,new DefaultThreadFactory("NIO-Server-Worker"));
        bootstrap.group(parentGroup, childGroup)
                .channelFactory(NioServerSocketChannel::new)
                .childHandler(new ChannelInitializer() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new Write6GBDataChannelHandler());
                    }
                });
        bootstrap.bind(8080).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                logger.info("place open Browser. enter http://localhost:8080  . Will not complete 6GB data");
            }
        });
    }

    private static class Write6GBDataChannelHandler extends SimpleChannelInboundHandler<HttpObject>{

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
            if(msg instanceof LastHttpContent){
//                new Thread(()->{
                // write body is 6G
                long testBodyLength = 1024L * 1024L * 1024L * 6L;

                HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
                httpResponse.headers().set("content-length",testBodyLength);
                httpResponse.headers().set("content-disposition", "attachment;");
                httpResponse.headers().set("content-type","application/octet-stream");
                ctx.write(httpResponse);

                // write body is 6G body
                while (testBodyLength > 0L){
                    int bufferSize = (int) Math.min(testBodyLength, 8192L * 2L);

                    testBodyLength -= bufferSize;

                    ByteBuf buffer = allocBuffer(ctx,bufferSize);
                    buffer.writeBytes(new byte[bufferSize]);
                    ctx.writeAndFlush(buffer);
                }
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
//                }).start();
            }
        }

        private ByteBuf allocBuffer(ChannelHandlerContext ctx, int size) {
            // monitor status
            SelectionKey selectionKey = ((AbstractNioChannel) ctx.channel()).selectionKey();
            boolean isFlushPending = selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
            long totalPendingWriteBytes = ctx.channel().unsafe().outboundBuffer().totalPendingWriteBytes();
            boolean inEventLoop = ctx.channel().eventLoop().inEventLoop();
            int pendingTasks = ((SingleThreadEventLoop) ctx.channel().eventLoop()).pendingTasks();

            try{
                return ctx.alloc().buffer(size);
            }catch (OutOfDirectMemoryError e){
                Assert.assertTrue("run at IO, is block. never trigger processSelectedKey() method. the event #OP_WRITE",
                        inEventLoop && totalPendingWriteBytes > 0);
                throw e;
            }finally {
                if(isFlushPending){
                    sleep(1000);
                }
                logger.info("isFlushPending = {}, totalPendingWriteBytes = {}/B, inEventLoop = {}, pendingTasks = {}",
                        isFlushPending,
                        totalPendingWriteBytes,
                        inEventLoop,
                        pendingTasks);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            logger.info("channelReadComplete = {}",ctx.channel());
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            logger.info("channelWritabilityChanged = {}",ctx.channel());
        }
    };

    private static void sleep(long millis){
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {}
    }
}
