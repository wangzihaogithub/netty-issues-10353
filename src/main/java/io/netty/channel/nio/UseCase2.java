package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.util.RunningHttpClientTest;
import io.netty.channel.nio.util.Server;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.vertx.core.json.JsonObject;

import java.nio.channels.SelectionKey;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * {@link HttpObjectEncoder#state} error occurred。'unexpected message type: xxx'
 *
 * case 1 ->
 *  if run at IO, is block. never trigger processSelectedKey() method. the event #OP_WRITE.
 *  causing the connection to hang without flushing data.
 *
 * case 2 ->
 *  if run at new thread, can trigger processSelectedKey() method. the event #OP_WRITE.
 *  but {@link HttpObjectEncoder#state} error occurred。'unexpected message type: xxx'
 *
 */
public class UseCase2 {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(UseCase2.class);

    public static void main(String[] args) throws InterruptedException {
        Server server = newServer();
        server.start(8080).sync();

        RunningHttpClientTest test = new RunningHttpClientTest();
        JsonObject body = new JsonObject("{\"body1\":\"post\"}");
        while (true) {
            test.doRequest(8080, "localhost", "/",body).await();
        }
    }

    private static Server<HttpObject> newServer(){
        Set<ChannelHandlerContext> contextSet = new LinkedHashSet<>();
        Executors.newScheduledThreadPool(1, new DefaultThreadFactory("ServerReport"))
                .scheduleAtFixedRate(()->{
                    for (ChannelHandlerContext ctx : contextSet) {
                        SelectionKey selectionKey = ((AbstractNioChannel) ctx.channel()).selectionKey();
                        boolean isFlushPending = selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
                        long totalPendingWriteBytes = ctx.channel().unsafe().outboundBuffer().totalPendingWriteBytes();
                        boolean inEventLoop = ctx.channel().eventLoop().inEventLoop();
                        int pendingTasks = ((SingleThreadEventLoop) ctx.channel().eventLoop()).pendingTasks();

                        logger.info("remote = {}, isFlushPending = {}, totalPendingWriteBytes = {}/B, inEventLoop = {}, pendingTasks = {}",
                                ctx.channel().remoteAddress(),
                                isFlushPending,
                                totalPendingWriteBytes,
                                inEventLoop,
                                pendingTasks);
                    }
                },5,5, TimeUnit.SECONDS);

        return new Server<HttpObject>(){
            private SingleThreadEventExecutor executor = new DefaultEventExecutor();

            @Override
            public void initChannel(Channel channel) {
                channel.pipeline().addLast(new HttpServerCodec());
                channel.pipeline().addLast(new ChunkedWriteHandler());
                channel.pipeline().addLast(this);
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                contextSet.add(ctx);
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
                if(msg instanceof LastHttpContent){
                    executor.execute(()->{
                        // write body is 2M
                        long testBodyLength = 1024L * 1024L * 2L;
//                        long testBodyLength = 0L;

                        HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK);
                        httpResponse.headers().set("content-length",testBodyLength);
                        httpResponse.headers().set("content-type","application/text");
                        ctx.write(httpResponse);

                        while (testBodyLength > 0L){
                            int bufferSize = (int) Math.min(testBodyLength, 8192L * 2L);

                            testBodyLength -= bufferSize;

                            ByteBuf buffer = ctx.alloc().buffer(bufferSize);
                            buffer.writeBytes(new byte[bufferSize]);
                            ctx.writeAndFlush(buffer);
                        }
                        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    });
                }
            }
        };
    }

}
