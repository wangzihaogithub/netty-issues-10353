package io.netty.channel.nio;

import io.netty.channel.nio.util.RunningHttpClientTest;
import io.netty.util.ResourceLeakDetector;
import io.vertx.core.json.JsonObject;

public class UseCase2Client {
    public static void main(String[] args) {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        RunningHttpClientTest test = new RunningHttpClientTest();
        JsonObject body = new JsonObject("{\"body1\":\"post\"}");
        while (true) {
            test.doRequest(8080, "localhost", "/",body).await();
        }
    }
}
