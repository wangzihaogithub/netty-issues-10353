package io.netty.channel.nio.util;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * running http test
 * @author wangzihaogithub
 * 2018/8/12/012
 */
public class RunningHttpClientTest {
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();
    private final AtomicLong totalSleepTime = new AtomicLong();
    private final WebClient client = WebClient.create(Vertx.vertx(), new WebClientOptions()
            .setTcpKeepAlive(false)
            .setKeepAlive(true));
    public RunningHttpClientTest() {
        new Print(this).start();
    }

    private final int queryCount = 10000;
    private final CountDownLatch latch = new CountDownLatch(queryCount);

    public RunningHttpClientTest doRequest(int port, String host, String uri, JsonObject body) {
        for (int i = 0; i < queryCount; i++) {
            client.get(port, host, uri).sendJsonObject(body,asyncResult -> {
                if (asyncResult.succeeded()) {
                    String bodyAsString = asyncResult.result().bodyAsString();
                    successCount.incrementAndGet();
                } else {
                    errorCount.incrementAndGet();
                    System.out.println("error = " + asyncResult.cause());
                }
                latch.countDown();
            });
        }
        return this;
    }

    public void await(){
        try {
            latch.await(10, TimeUnit.SECONDS);
            Thread.sleep(10);
            totalSleepTime.addAndGet(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static class Print extends Thread {
        private final RunningHttpClientTest test;
        private AtomicInteger printCount = new AtomicInteger();
        private long beginTime = System.currentTimeMillis();
        static final Logger logger = LoggerFactory.getLogger(Print.class);

        Print(RunningHttpClientTest test) {
            super("ClientReport");
            this.test = test;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    sleep(5000);
                    long totalTime = System.currentTimeMillis() - beginTime - test.totalSleepTime.get();
                    printQps(test.successCount.get(), test.errorCount.get(), totalTime);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        private void printQps(int successCount, int errorCount, long totalTime) {
            if (successCount == 0) {
                logger.info("No successful call");
            } else {
                logger.info(
                        "(" + printCount.incrementAndGet() + "), " +
                                (totalTime / 60000) + "m" + ((totalTime % 60000) / 1000) + "s, " +
                                "success = " + successCount + ", " +
                                "error = " + errorCount + ", " +
                                "avg = " + new BigDecimal((double) totalTime / (double) successCount).setScale(2, BigDecimal.ROUND_HALF_DOWN).stripTrailingZeros().toPlainString() + "ms, " +
                                "qps = " + new BigDecimal((double) successCount / (double) totalTime * 1000).setScale(2, BigDecimal.ROUND_HALF_DOWN).stripTrailingZeros().toPlainString()
//                            +
//                            "\r\n==============================="
                );
            }
        }
    }


}
