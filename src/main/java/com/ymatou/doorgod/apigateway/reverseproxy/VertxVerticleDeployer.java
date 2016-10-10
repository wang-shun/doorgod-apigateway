package com.ymatou.doorgod.apigateway.reverseproxy;

import com.ymatou.doorgod.apigateway.config.AppConfig;
import com.ymatou.doorgod.apigateway.integration.MySqlClient;
import com.ymatou.doorgod.apigateway.model.TargetServer;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.List;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/**
 * 在Spring Application context启动完毕后，部署Vertx verticles
 * Created by tuwenjie on 2016/9/21.
 */
@Component
public class VertxVerticleDeployer {

    //vertice实例个数
    public static final int VERTICLE_INSTANCES = VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE;

    public static final String ADDRESS_START_WARMUP_TARGET_SERVER = "address-start-warmup";

    public static final String ADDRESS_END_WARMUP_TARGET_SERVER = "address-end-warmup";

    public static final String WARM_UP_SUCCESS_MSG = "ok";

    public static TargetServer targetServer = null;

    public static Vertx vertx = null;

    public static boolean success = false;

    @Autowired
    private MySqlClient mySqlClient;

    @Autowired
    private AppConfig appConfig;

    private static Logger LOGGER = LoggerFactory.getLogger(VertxVerticleDeployer.class);

    public void deployVerticles() throws Exception {

        //当前无需更多配置
        VertxOptions vertxOptions = new VertxOptions();
        vertx = Vertx.vertx(vertxOptions);

        try {
            targetServer = mySqlClient.locateTargetServer();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        CountDownLatch latch = new CountDownLatch(1);

        Throwable[] throwables = new Throwable[]{null};

        vertx.deployVerticle(HttpServerVerticle.class.getName(),
                new DeploymentOptions().setInstances(VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE),
                result -> {
                    if (result.failed()) {
                        throwables[0] = result.cause();
                    }
                    latch.countDown();
                });

        //等待Verticles部署完成
        latch.await();


        if (throwables[0] != null) {
            throw new RuntimeException("Failed to startup ApiGateway", throwables[0]);
        }

        boolean[] warmUpSuccess = new boolean[]{true};

        CountDownLatch warmupLatch = new CountDownLatch(VERTICLE_INSTANCES);

        vertx.eventBus().consumer(ADDRESS_END_WARMUP_TARGET_SERVER, event -> {
            if ( !event.body().toString().equals(WARM_UP_SUCCESS_MSG)) {
                warmUpSuccess[0] = false;
            }
            warmupLatch.countDown();
        });

        //通知各个Verticle去预创建到TargetServer的连接
        vertx.eventBus().publish(ADDRESS_START_WARMUP_TARGET_SERVER, "");

        //等待各个Verticle预创建连接完毕
        warmupLatch.await();

        if (!warmUpSuccess[0]) {
            throw new RuntimeException("Failed to startup ApiGateway because warmming up target server failed.");
        }

        success = true;
        LOGGER.info("Succeed in startup ApiGateway");
    }

    @PreDestroy
    public void destroy() {
        vertx.close();
    }

}