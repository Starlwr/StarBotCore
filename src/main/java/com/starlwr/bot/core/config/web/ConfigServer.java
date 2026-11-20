package com.starlwr.bot.core.config.web;

import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 独立配置中心 HTTP 服务
 */
@Slf4j
public class ConfigServer {
    /**
     * 独立配置页面所用的 HTTP 服务
     */
    private final HttpServer server;

    /**
     * 处理配置请求的线程池
     */
    private final ThreadPoolExecutor executor;

    /**
     * 配置中心请求处理器
     */
    private final ConfigWebEndpoint endpoint;

    public ConfigServer(String host, int port, ConfigWebEndpoint endpoint) throws IOException {
        this.endpoint = endpoint;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 32);
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "starbot-config-http");
            thread.setDaemon(false);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/config", exchange -> {
            try (exchange) {
                ConfigWebEndpoint.Response result = endpoint.handle(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Origin"),
                        exchange.getRequestHeaders().getFirst("Host"), exchange::getRequestBody);
                exchange.getResponseHeaders().set("Content-Type", result.getContentType());
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                result.getHeaders().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(result.getStatus(), result.getBody().length == 0 ? -1 : result.getBody().length);
                exchange.getResponseBody().write(result.getBody());
            }
        });
    }

    /**
     * 启动配置中心 HTTP 服务
     */
    public void start() {
        server.start();
    }

    /**
     * 停止 HTTP 服务并关闭请求处理线程池
     */
    public void stop() {
        server.stop(0);
        executor.shutdownNow();
        try {
            endpoint.destroy();
        } catch (IOException e) {
            log.warn("配置动作资源关闭失败", e);
        }
    }

    /**
     * 获取服务实际监听的端口
     * @return 实际监听端口
     */
    public int getPort() {
        return server.getAddress().getPort();
    }
}
