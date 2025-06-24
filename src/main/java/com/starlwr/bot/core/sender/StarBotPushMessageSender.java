package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.StringUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * StarBot 推送消息发送器
 */
@Slf4j
@Service
public class StarBotPushMessageSender {
    @Resource
    private StarBotCoreProperties properties;

    @Resource
    private HttpUtil http;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, BlockingQueue<Message>> queueMap = new ConcurrentHashMap<>();

    private final Map<String, Future<?>> platformTasks = new ConcurrentHashMap<>();

    /**
     * 将消息加入至消息队列
     * @param message 消息
     */
    public void send(Message message) {
        BlockingQueue<Message> queue = queueMap.computeIfAbsent(message.getPlatform(), k -> {
            BlockingQueue<Message> newQueue = new LinkedBlockingQueue<>();
            startPlatformThread(k, newQueue);
            return newQueue;
        });

        try {
            queue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("添加消息: {} 到队列时被中断", JSON.toJSONString(message), e);
        }
    }

    /**
     * 启动平台发送线程
     * @param platform 平台
     * @param queue 消息队列
     */
    private void startPlatformThread(String platform, BlockingQueue<Message> queue) {
        platformTasks.computeIfAbsent(platform, p -> executor.submit(() -> {
            Thread.currentThread().setName("sender-" + platform);
            log.info("{} 平台消息发送线程已启动", platform);
            long delay = properties.getSender().get(platform).getDelay();
            long id = 1;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Message message = queue.take();
                    message.setId(id++);
                    doSend(message);
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("{} 平台发送线程中断", platform, e);
                } catch (Exception e) {
                    log.error("{} 平台消息发送异常", platform, e);
                }
            }
            return null;
        }));
    }

    /**
     * 发送消息
     * @param message 消息
     */
    private void doSend(Message message) {
        Sender sender = properties.getSender().get(message.getPlatform());

        String platform = StringUtil.isNotBlank(sender.getName()) ? sender.getName() : message.getPlatform();

        Map<String, String> headers = new HashMap<>();
        if (StringUtil.isNotBlank(sender.getToken())) {
            headers.put("Authorization", "Bearer " + sender.getToken());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", message.getType().getCode());
        params.put("num", message.getNum());
        params.put("content", message.getContent());
        params.put("timestamp", message.getTimestamp().toEpochMilli());

        JSONObject result = http.postJson(sender.getUrl(), headers, params);
        if (result.getInteger("code") == 0) {
            log.info("StarBot -> {} ([{}] {}) [{}]: {}", platform, message.getType().getStr(), message.getNum(), message.getId(), message.getDisplay());
        } else {
            log.error("消息发送失败 ({}): StarBot -> {} ([{}] {}) [{}]: {}", result.getString("message"), platform, message.getType().getStr(), message.getNum(), message.getId(), message.getDisplay());
        }
    }
}
