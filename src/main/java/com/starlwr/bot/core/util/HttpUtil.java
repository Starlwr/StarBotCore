package com.starlwr.bot.core.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 请求工具类
 */
@Slf4j
@Component
public class HttpUtil {
    @Resource
    @Qualifier("networkThreadPool")
    private ThreadPoolTaskExecutor executor;

    @Resource
    private WebClient webClient;

    @SuppressWarnings("SpellCheckingInspection")
    private final List<String> USER_AGENTS = Arrays.asList(
            "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Win64; x64; Trident/5.0; .NET CLR 3.5.30729; .NET CLR 3.0.30729; .NET CLR 2.0.50727; Media Center PC 6.0)",
            "Mozilla/5.0 (compatible; MSIE 8.0; Windows NT 6.0; Trident/4.0; WOW64; Trident/4.0; SLCC2; .NET CLR 2.0.50727; .NET CLR 3.5.30729; .NET CLR 3.0.30729; .NET CLR 1.0.3705; .NET CLR 1.1.4322)",
            "Mozilla/4.0 (compatible; MSIE 7.0b; Windows NT 5.2; .NET CLR 1.1.4322; .NET CLR 2.0.50727; InfoPath.2; .NET CLR 3.0.04506.30)",
            "Mozilla/5.0 (Windows; U; Windows NT 5.1; zh-CN) AppleWebKit/523.15 (KHTML, like Gecko, Safari/419.3) Arora/0.3 (Change: 287 c9dfb30)",
            "Mozilla/5.0 (X11; U; Linux; en-US) AppleWebKit/527+ (KHTML, like Gecko, Safari/419.3) Arora/0.6",
            "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.8.1.2pre) Gecko/20070215 K-Ninja/2.1.1",
            "Mozilla/5.0 (Windows; U; Windows NT 5.1; zh-CN; rv:1.9) Gecko/20080705 Firefox/3.0 Kapiko/3.0",
            "Mozilla/5.0 (X11; Linux i686; U;) Gecko/20070322 Kazehakase/0.4.5"
    );

    /**
     * 获取随机用户代理字符串
     *
     * @return 随机用户代理字符串
     */
    public String getRandomUserAgent() {
        return USER_AGENTS.get(new Random().nextInt(USER_AGENTS.size()));
    }

    /**
     * 同步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public String get(String url) {
        return get(url, new HashMap<>());
    }

    /**
     * 异步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<String> asyncGet(String url) {
        return CompletableFuture.supplyAsync(() -> get(url), executor);
    }

    /**
     * 自定义请求头的同步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public String get(String url, Map<String, String> headers) {
        return webClient.get()
                .uri(url)
                .headers(httpHeaders -> headers.forEach(httpHeaders::add))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * 自定义请求头的异步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<String> asyncGet(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> get(url, headers), executor);
    }

    /**
     * 读取 JSON 的同步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public JSONObject getJson(String url) {
        return JSON.parseObject(get(url));
    }

    /**
     * 读取 JSON 的异步 HTTP GET 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncGetJson(String url) {
        return CompletableFuture.supplyAsync(() -> getJson(url), executor);
    }

    /**
     * 自定义请求头读取 JSON 的同步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public JSONObject getJson(String url, Map<String, String> headers) {
        return JSON.parseObject(get(url, headers));
    }

    /**
     * 自定义请求头读取 JSON 的异步 HTTP GET 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncGetJson(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> getJson(url, headers), executor);
    }

    /**
     * 读取字节的同步 HTTP GET 请求
     * @param url URL
     * @return 请求结果
     */
    public byte[] getBytes(String url) {
        return getBytes(url, new HashMap<>());
    }

    /**
     * 读取字节的异步 HTTP GET 请求
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<byte[]> asyncGetBytes(String url) {
        return CompletableFuture.supplyAsync(() -> getBytes(url), executor);
    }

    /**
     * 自定义请求头读取字节的同步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 请求结果
     */
    public byte[] getBytes(String url, Map<String, String> headers) {
        return webClient.get()
                .uri(url)
                .headers(httpHeaders -> headers.forEach(httpHeaders::add))
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }

    /**
     * 自定义请求头读取字节的异步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<byte[]> asyncGetBytes(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> getBytes(url, headers), executor);
    }

    /**
     * 读取图片的同步 HTTP GET 请求
     * @param url URL
     * @return 图片
     */
    public Optional<BufferedImage> getBufferedImage(String url) {
        return getBufferedImage(url, new HashMap<>());
    }

    /**
     * 读取图片的异步 HTTP GET 请求
     * @param url URL
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBufferedImage(String url) {
        return CompletableFuture.supplyAsync(() -> getBufferedImage(url), executor);
    }

    /**
     * 自定义请求头读取图片的同步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 图片
     */
    public Optional<BufferedImage> getBufferedImage(String url, Map<String, String> headers) {
        try {
            byte[] bytes = getBytes(url, headers);

            if (bytes != null) {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return Optional.ofNullable(ImageIO.read(input));
            }
        } catch (Exception e) {
            log.error("从 {} 读取图片异常", url, e);
        }

        return Optional.empty();
    }

    /**
     * 自定义请求头读取图片的异步 HTTP GET 请求
     * @param url URL
     * @param headers HTTP 请求头
     * @return 图片
     */
    public CompletableFuture<Optional<BufferedImage>> asyncGetBufferedImage(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> getBufferedImage(url, headers), executor);
    }

    /**
     * 同步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public String post(String url) {
        return post(url, new HashMap<>(), new HashMap<>());
    }

    /**
     * 异步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPost(String url) {
        return CompletableFuture.supplyAsync(() -> post(url), executor);
    }

    /**
     * 自定义请求头的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public String postWithHeaders(String url, Map<String, String> headers) {
        return post(url, headers, new HashMap<>());
    }

    /**
     * 自定义请求头的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostWithHeaders(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> postWithHeaders(url, headers), executor);
    }

    /**
     * 自定义请求参数的同步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public String postWithParams(String url, Object params) {
        return post(url, new HashMap<>(), params);
    }

    /**
     * 自定义请求参数的异步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPostWithParams(String url, Object params) {
        return CompletableFuture.supplyAsync(() -> postWithParams(url, params), executor);
    }

    /**
     * 自定义请求头和请求参数的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public String post(String url, Map<String, String> headers, Object params) {
        return webClient.post()
                .uri(url)
                .headers(httpHeaders -> headers.forEach(httpHeaders::add))
                .bodyValue(params)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * 自定义请求头和请求参数的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<String> asyncPost(String url, Map<String, String> headers, Object params) {
        return CompletableFuture.supplyAsync(() -> post(url, headers, params), executor);
    }

    /**
     * 读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public JSONObject postJson(String url) {
        return JSON.parseObject(post(url));
    }

    /**
     * 读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJson(String url) {
        return CompletableFuture.supplyAsync(() -> postJson(url), executor);
    }

    /**
     * 自定义请求头读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public JSONObject postJsonWithHeaders(String url, Map<String, String> headers) {
        return JSON.parseObject(postWithHeaders(url, headers));
    }

    /**
     * 自定义请求头读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonWithHeaders(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> postJsonWithHeaders(url, headers), executor);
    }

    /**
     * 自定义请求参数读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public JSONObject postJsonWithParams(String url, Object params) {
        return JSON.parseObject(postWithParams(url, params));
    }

    /**
     * 自定义请求参数读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJsonWithParams(String url, Object params) {
        return CompletableFuture.supplyAsync(() -> postJsonWithParams(url, params), executor);
    }

    /**
     * 自定义请求头和请求参数读取 JSON 的同步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public JSONObject postJson(String url, Map<String, String> headers, Object params) {
        return JSON.parseObject(post(url, headers, params));
    }

    /**
     * 自定义请求头和请求参数读取 JSON 的异步 HTTP POST 请求
     *
     * @param url URL
     * @param headers   HTTP 请求头
     * @param params    HTTP 请求参数
     * @return 请求结果
     */
    public CompletableFuture<JSONObject> asyncPostJson(String url, Map<String, String> headers, Object params) {
        return CompletableFuture.supplyAsync(() -> postJson(url, headers, params), executor);
    }
}
