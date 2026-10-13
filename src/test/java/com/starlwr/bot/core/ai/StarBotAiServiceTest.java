package com.starlwr.bot.core.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.StarBotCoreThreadPoolConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 服务测试
 * <p>
 * 使用本机 HTTP 服务验证两种协议的实际请求、回复解析、结构化输出及 SSE。
 */
class StarBotAiServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import({StarBotCoreProperties.class, StarBotCoreThreadPoolConfig.class, AiChatModelFactory.class, StarBotAiService.class})
    static class TestConfig {
        @Bean
        PluginClient pluginClient(StarBotAiService service) {
            return new PluginClient(service);
        }
    }

    private record PluginClient(StarBotAiService aiService) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    /**
     * 未配置模型的既有部署仍能正常创建服务，插件可以注入同一个 StarBotAiService
     */
    @Test
    void startsWithoutModelsAndInjectsServiceIntoPlugins() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(StarBotAiService.class);
            StarBotAiService service = context.getBean(StarBotAiService.class);
            assertThat(service.getModelNames()).isEmpty();
            assertThatThrownBy(() -> service.chat("你好")).isInstanceOf(IllegalStateException.class)
                    .hasMessage("AI 服务不可用，请先在 starbot.core.ai.models 中配置模型");
            assertThat(context.getBean(StarBotCoreProperties.class).getAi().getDefaultModel()).isNull();
            assertThat(context.getBean(StarBotCoreProperties.class).getAiThread().getQueueCapacity()).isEqualTo(10);
            assertThat(new AiModel().getStructuredOutputMaxRepeatAttempts()).isEqualTo(3);
            assertThat(context.getBean(PluginClient.class).aiService()).isSameAs(service);
        });
    }

    /**
     * 验证只有一个模型时无需配置 default-model，默认调用自动选择该模型
     */
    @Test
    void startsWithSingleModelWithoutDefaultModel() {
        runner.withPropertyValues(
                "starbot.core.ai.models.local.provider=OPENAI",
                "starbot.core.ai.models.local.base-url=http://127.0.0.1",
                "starbot.core.ai.models.local.api-key=local-key",
                "starbot.core.ai.models.local.model=test-model"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StarBotCoreProperties.class).getAi().getDefaultModel()).isNull();
            StarBotAiService service = context.getBean(StarBotAiService.class);
            assertThat(service.getChatClient()).isSameAs(service.getChatClient("local"));
        });
    }

    /**
     * 验证配置多个模型时必须手动指定 default-model，不自动选择其中一个
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMultipleModelsWithoutDefaultModel(String defaultModel) {
        ApplicationContextRunner configuredRunner = runner.withPropertyValues(
                "starbot.core.ai.models.first.provider=OPENAI",
                "starbot.core.ai.models.first.base-url=http://127.0.0.1",
                "starbot.core.ai.models.first.api-key=local-key",
                "starbot.core.ai.models.first.model=first-model",
                "starbot.core.ai.models.second.provider=OPENAI",
                "starbot.core.ai.models.second.base-url=http://127.0.0.1",
                "starbot.core.ai.models.second.api-key=local-key",
                "starbot.core.ai.models.second.model=second-model"
        );
        if (defaultModel != null) {
            configuredRunner = configuredRunner.withPropertyValues("starbot.core.ai.default-model=" + defaultModel);
        }
        configuredRunner.run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("配置多个 AI 模型时必须指定默认模型 default-model"));
    }

    /**
     * 验证两种协议均要求手动配置 base-url，未配置或仅包含空白时在启动阶段报错
     */
    @ParameterizedTest
    @MethodSource("missingAndBlankBaseUrls")
    void rejectsMissingOrBlankBaseUrl(AiProvider provider, String baseUrl) {
        ApplicationContextRunner configuredRunner = runner.withPropertyValues(
                "starbot.core.ai.models.local.provider=" + provider,
                "starbot.core.ai.models.local.api-key=local-key",
                "starbot.core.ai.models.local.model=test-model"
        );
        if (baseUrl != null) {
            configuredRunner = configuredRunner.withPropertyValues("starbot.core.ai.models.local.base-url=" + baseUrl);
        }
        configuredRunner.run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("AI 模型 local 模型配置的 base-url 不能为空"));
    }

    /**
     * 验证多模型、环境变量占位符、超时、结构化修复和独立线程池参数均可通过配置绑定
     */
    @Test
    void bindsNamedModelsAndThreadPool() {
        runner.withPropertyValues(
                "test.ai.key=local-key",
                "starbot.core.ai.default-model=local",
                "starbot.core.ai.models.local.provider=OPENAI",
                "starbot.core.ai.models.local.base-url=https://api.deepseek.com",
                "starbot.core.ai.models.local.api-key=${test.ai.key}",
                "starbot.core.ai.models.local.model=deepseek-flash",
                "starbot.core.ai.models.local.timeout=7s",
                "starbot.core.ai.models.local.structured-output-max-repeat-attempts=2",
                "starbot.core.ai.models.local.extra-body.thinking.type=disabled",
                "starbot.core.ai-thread.core-pool-size=1",
                "starbot.core.ai-thread.max-pool-size=3",
                "starbot.core.ai-thread.queue-capacity=5"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            StarBotCoreProperties properties = context.getBean(StarBotCoreProperties.class);
            AiModel model = properties.getAi().getModels().get("local");
            assertThat(model.getApiKey()).isEqualTo("local-key");
            assertThat(model.getTimeout()).isEqualTo(Duration.ofSeconds(7));
            assertThat(model.getStructuredOutputMaxRepeatAttempts()).isEqualTo(2);
            assertThat(model.getProvider()).isEqualTo(AiProvider.OPENAI);
            assertThat(model.getExtraBody()).containsEntry("thinking", Map.of("type", "disabled"));
            assertThat(properties.getAiThread().getQueueCapacity()).isEqualTo(5);
            ThreadPoolTaskExecutor executor = context.getBean("aiThreadPool", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(3);
            assertThat(context.getBean(StarBotAiService.class).getModelNames()).containsExactly("local");
        });
    }

    /**
     * 验证未配置模型时，内存 YAML 中的线程池参数能绑定到统一 Core 配置
     */
    @Test
    void bindsYamlToCoreProperties() throws IOException {
        String yaml = """
                starbot:
                  core:
                    ai-thread:
                      core-pool-size: 2
                      max-pool-size: 4
                      queue-capacity: 6
                """;
        PropertySource<?> source = new YamlPropertySourceLoader()
                .load("test-ai", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))).get(0);
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addLast(source))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StarBotCoreProperties properties = context.getBean(StarBotCoreProperties.class);
                    assertThat(properties.getAi().getModels()).isEmpty();
                    assertThat(properties.getAi().getDefaultModel()).isNull();
                    assertThat(context.getBean(StarBotAiService.class).getModelNames()).isEmpty();
                    assertThat(properties.getAiThread().getCorePoolSize()).isEqualTo(2);
                    assertThat(properties.getAiThread().getMaxPoolSize()).isEqualTo(4);
                    assertThat(properties.getAiThread().getQueueCapacity()).isEqualTo(6);
                    ThreadPoolTaskExecutor executor = context.getBean("aiThreadPool", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(4);
                    assertThat(executor.getQueueCapacity()).isEqualTo(6);
                });
    }

    /**
     * 验证启用模型缺少密钥时在启动阶段明确报错
     */
    @Test
    void rejectsMissingCredentialsWhenEnabled() {
        runner.withPropertyValues(
                "starbot.core.ai.default-model=local",
                "starbot.core.ai.models.local.provider=OPENAI",
                "starbot.core.ai.models.local.base-url=http://127.0.0.1",
                "starbot.core.ai.models.local.model=test-model"
        ).run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseMessage("AI 模型 local 模型配置的 api-key 不能为空"));
    }

    /**
     * 验证结构化自动修复次数不能配置为负数
     */
    @Test
    void rejectsNegativeStructuredRepairAttempts() {
        runner.withPropertyValues(
                "starbot.core.ai.default-model=local",
                "starbot.core.ai.models.local.provider=OPENAI",
                "starbot.core.ai.models.local.base-url=http://127.0.0.1",
                "starbot.core.ai.models.local.api-key=local-key",
                "starbot.core.ai.models.local.model=test-model",
                "starbot.core.ai.models.local.structured-output-max-repeat-attempts=-1"
        ).run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("AI 模型 local 模型配置的 structured-output-max-repeat-attempts 不能小于 0"));
    }

    /**
     * 验证已配置模型时 default-model 必须指向其中一个配置
     */
    @Test
    void rejectsMissingDefaultModel() {
        runner.withPropertyValues(
                        "starbot.core.ai.default-model=missing",
                        "starbot.core.ai.models.local.provider=OPENAI",
                        "starbot.core.ai.models.local.base-url=http://127.0.0.1",
                        "starbot.core.ai.models.local.api-key=local-key",
                        "starbot.core.ai.models.local.model=test-model")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("默认 AI 模型 default-model 必须为已配置的模型"));
    }

    /**
     * 本地协议调用测试，仅在执行该组用例时创建 HTTP 服务
     */
    @Nested
    class ProtocolTests {
        private HttpServer server;

        private ExecutorService serverExecutor;

        private ThreadPoolTaskExecutor executor;

        private AnnotationConfigApplicationContext context;

        private StarBotCoreProperties properties;

        private final List<Request> requests = new CopyOnWriteArrayList<>();

        private final Queue<String> responseTexts = new ConcurrentLinkedQueue<>();

        private volatile String responseText = "你好";

        private volatile int responseStatus = 200;

        private volatile long responseDelay;

        @BeforeEach
        void setup() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            serverExecutor = Executors.newCachedThreadPool();
            server.setExecutor(serverExecutor);
            server.createContext("/", this::handle);
            server.start();
            properties = new StarBotCoreProperties();
            properties.getAi().setDefaultModel("default");
            context = new AnnotationConfigApplicationContext();
            context.registerBean(StarBotCoreProperties.class, () -> properties);
            context.registerBean("aiThreadPool", ThreadPoolTaskExecutor.class,
                    () -> new StarBotCoreThreadPoolConfig(properties).aiThreadPool());
            context.registerBean(AiChatModelFactory.class);
            context.registerBean(StarBotAiService.class, definition -> definition.setLazyInit(true));
            context.refresh();
            executor = context.getBean("aiThreadPool", ThreadPoolTaskExecutor.class);
        }

        @AfterEach
        void cleanup() {
            context.close();
            server.stop(0);
            serverExecutor.shutdownNow();
        }

        /**
         * 验证两种协议均能携带认证、请求头、系统提示词、历史消息和调用参数
         */
        @ParameterizedTest
        @EnumSource(AiProvider.class)
        void sendsProtocolSpecificRequestsAndReadsUsage(AiProvider provider) {
            StarBotAiService service = createService(provider);
            Prompt prompt = new Prompt(List.of(new SystemMessage("插件提示词"), new UserMessage("上一条消息"),
                    new AssistantMessage("上一条回复"), new UserMessage("当前消息")),
                    ChatOptions.builder().maxTokens(32).build());

            ChatResponse response = service.chat(prompt);

            assertEquals("你好", response.getResult().getOutput().getText());
            assertEquals(12, response.getMetadata().getUsage().getTotalTokens());
            assertEquals(1, requests.size());
            Request request = requests.get(0);
            assertEquals("test-value", request.header("X-Test"));
            assertEquals("test-model", request.body().getString("model"));
            assertEquals(32, request.body().getIntValue("max_tokens"));
            assertTrue(request.body().toJSONString().contains("插件提示词"));
            assertTrue(request.body().toJSONString().contains("当前消息"));
            if (provider == AiProvider.ANTHROPIC) {
                assertEquals("/gateway/v1/messages", request.path());
                assertEquals("test-key", request.header("x-api-key"));
                assertNotNull(request.header("anthropic-version"));
                assertNotNull(request.body().get("system"));
            } else {
                assertEquals("/gateway/chat/completions", request.path());
                assertEquals("Bearer test-key", request.header("Authorization"));
                assertEquals("custom-value", request.body().getString("custom_parameter"));
            }
        }

        /**
         * 验证 DeepSeek 模型使用两种通用协议配置，保留网关前缀及 OpenAI 非思考参数
         */
        @ParameterizedTest
        @EnumSource(AiProvider.class)
        void usesCompatibleProtocolsForDeepSeek(AiProvider provider) {
            AiModel config = model(provider);
            config.setModel("deepseek-flash");
            if (provider == AiProvider.OPENAI) {
                config.setExtraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                config.setBaseUrl(config.getBaseUrl() + "/anthropic");
            }
            properties.getAi().getModels().put("default", config);
            StarBotAiService service = context.getBean(StarBotAiService.class);

            assertEquals("你好", service.chat("你好"));

            assertEquals(1, requests.size());
            Request request = requests.get(0);
            assertEquals("deepseek-flash", request.body().getString("model"));
            if (provider == AiProvider.OPENAI) {
                assertEquals("/gateway/chat/completions", request.path());
                assertEquals("disabled", request.body().getJSONObject("thinking").getString("type"));
            } else {
                assertEquals("/gateway/anthropic/v1/messages", request.path());
                assertEquals("test-key", request.header("x-api-key"));
            }
        }

        /**
         * 验证流式调用在订阅时发出请求，并能解析各协议的 SSE 数据
         */
        @ParameterizedTest
        @EnumSource(AiProvider.class)
        void streamsWithoutRequestingBeforeSubscription(AiProvider provider) {
            StarBotAiService service = createService(provider);
            Flux<ChatResponse> stream = service.stream(new Prompt("你好"));
            assertTrue(requests.isEmpty());

            List<ChatResponse> responses = stream.collectList().block(Duration.ofSeconds(5));

            assertNotNull(responses);
            StringBuilder content = new StringBuilder();
            for (ChatResponse response : responses) {
                if (response.getResult() != null && response.getResult().getOutput().getText() != null) {
                    content.append(response.getResult().getOutput().getText());
                }
            }
            assertEquals("你好", content.toString());
            assertEquals(1, requests.size());
            assertTrue(requests.get(0).body().getBooleanValue("stream"));
        }

        /**
         * 验证两种协议的结构化回复均使用通用 JSON 格式要求，不强制要求原生 JSON Schema 支持
         */
        @ParameterizedTest
        @EnumSource(AiProvider.class)
        void convertsStructuredRepliesSynchronouslyAndAsynchronously(AiProvider provider) throws Exception {
            responseText = "{\"comment\":\"很好\",\"score\":3}";
            StarBotAiService service = createService(provider);

            Review review = service.entity(new Prompt("返回评价"), Review.class);
            Review asyncReview = service.entityAsync(new Prompt("返回评价"), Review.class).get(5, TimeUnit.SECONDS);

            assertEquals(new Review("很好", 3), review);
            assertEquals(review, asyncReview);
            assertEquals(2, requests.size());
            for (Request request : requests) {
                assertTrue(request.body().toJSONString().contains("score"));
                assertFalse(request.body().containsKey("response_format"));
                assertFalse(request.body().containsKey("output_config"));
            }
        }

        /**
         * 验证包含泛型的结构化类型能够正确解析
         */
        @Test
        void convertsGenericStructuredReplies() {
            responseText = "[{\"comment\":\"很好\",\"score\":3}]";
            StarBotAiService service = createService(AiProvider.OPENAI);

            List<Review> reviews = service.entity(new Prompt("返回评价列表"), new ParameterizedTypeReference<>() {
            });

            assertEquals(List.of(new Review("很好", 3)), reviews);
        }

        /**
         * 验证两种协议在结构化校验失败后默认会请求修复，并返回修复后的结果
         */
        @ParameterizedTest
        @EnumSource(AiProvider.class)
        void repairsInvalidStructuredRepliesByDefault(AiProvider provider) {
            responseTexts.add("{\"comment\":\"很好\",\"score\":\"错误类型\"}");
            responseText = "{\"comment\":\"很好\",\"score\":3}";
            StarBotAiService service = createService(provider);

            Review review = service.entity(new Prompt("返回评价"), Review.class);

            assertEquals(new Review("很好", 3), review);
            assertEquals(2, requests.size());
        }

        /**
         * 验证修复次数有明确上限，设为 0 时仍校验并直接报错
         */
        @ParameterizedTest
        @ValueSource(ints = {0, 1, 3})
        void limitsStructuredRepairAttemptsAndRetainsValidation(int maxRepeatAttempts) {
            responseText = "{\"comment\":\"很好\",\"score\":\"错误类型\"}";
            AiModel config = model(AiProvider.OPENAI);
            config.setStructuredOutputMaxRepeatAttempts(maxRepeatAttempts);
            properties.getAi().getModels().put("default", config);
            StarBotAiService service = context.getBean(StarBotAiService.class);

            assertThrows(RuntimeException.class, () -> service.entity(new Prompt("返回评价"), Review.class));

            assertEquals(1 + maxRepeatAttempts, requests.size());
        }

        /**
         * 验证多组命名配置可以独立选择，默认客户端按 default-model 路由
         */
        @Test
        void routesNamedAndDefaultModelsIndependently() {
            properties.getAi().setDefaultModel("second");
            properties.getAi().getModels().put("default", model(AiProvider.OPENAI));
            AiModel second = model(AiProvider.ANTHROPIC);
            second.setModel("second-model");
            properties.getAi().getModels().put("second", second);
            StarBotAiService service = context.getBean(StarBotAiService.class);

            assertEquals("你好", service.chat("你好"));
            service.chatAsync("default", new Prompt("你好")).join();

            assertEquals("second-model", requests.get(0).body().getString("model"));
            assertEquals("test-model", requests.get(1).body().getString("model"));
            assertEquals(2, service.getModelNames().size());
            assertThrows(UnsupportedOperationException.class, () -> service.getModelNames().clear());
            assertThrows(IllegalArgumentException.class, () -> service.getChatClient("missing"));
            assertEquals(2, requests.size());
        }

        /**
         * 验证未指定默认模型时，默认调用会使用唯一配置的模型
         */
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        void usesOnlyModelWhenDefaultModelIsNotSpecified(String defaultModel) {
            properties.getAi().setDefaultModel(defaultModel);
            properties.getAi().getModels().put("only", model(AiProvider.OPENAI));
            StarBotAiService service = context.getBean(StarBotAiService.class);

            assertEquals("你好", service.chat("你好"));

            assertEquals(1, requests.size());
            assertEquals("test-model", requests.get(0).body().getString("model"));
        }

        /**
         * 验证未配置模型时调用明确报错且不发起网络请求
         */
        @Test
        void unconfiguredServiceDoesNotRequireCredentials() {
            StarBotAiService service = context.getBean(StarBotAiService.class);

            assertTrue(service.getModelNames().isEmpty());
            assertThrows(IllegalStateException.class, () -> service.chat("你好"));
            assertTrue(service.chatAsync(new Prompt("你好")).isCompletedExceptionally());
            assertTrue(service.entityAsync(new Prompt("返回评价"), Review.class).isCompletedExceptionally());
            assertThrows(IllegalStateException.class, () -> service.stream(new Prompt("你好")).blockLast());
            assertTrue(requests.isEmpty());
        }

        /**
         * 验证配置模型后无需额外开关即可启用 AI，创建客户端不会发起请求
         */
        @Test
        void enablesAutomaticallyWhenModelIsConfigured() {
            StarBotAiService service = createService(AiProvider.OPENAI);

            assertEquals(Set.of("default"), service.getModelNames());
            assertTrue(requests.isEmpty());
        }

        /**
         * 验证异步任务在提交时复制输入，等待期间修改原始消息不会污染请求
         */
        @Test
        void snapshotsMessagesBeforeQueuingAsyncRequests() throws Exception {
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            StarBotAiService service = createService(AiProvider.OPENAI);
            Prompt prompt = new Prompt(new ArrayList<>(List.of(new UserMessage("原始消息"))));
            CompletableFuture<ChatResponse> result = service.chatAsync(prompt);
            prompt.getInstructions().clear();
            release.countDown();

            assertEquals("你好", result.get(5, TimeUnit.SECONDS).getResult().getOutput().getText());
            assertTrue(requests.get(0).body().toJSONString().contains("原始消息"));
        }

        /**
         * 验证线程池拒绝和服务关闭时异步接口能完成 future，不让调用方无限等待
         */
        @Test
        void completesAsyncFailuresWhenExecutorOrServiceIsClosed() {
            StarBotAiService service = createService(AiProvider.OPENAI);
            executor.shutdown();

            CompletableFuture<ChatResponse> rejected = service.chatAsync(new Prompt("你好"));
            assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS));
            CompletableFuture<Review> rejectedReview = service.entityAsync(new Prompt("返回评价"), Review.class);
            assertThrows(ExecutionException.class, () -> rejectedReview.get(1, TimeUnit.SECONDS));
            context.close();
            assertEquals("AI 服务已关闭", assertThrows(IllegalStateException.class, () -> service.chat("你好")).getMessage());
            CompletableFuture<ChatResponse> closed = service.chatAsync(new Prompt("你好"));
            assertEquals("AI 服务已关闭", assertThrows(ExecutionException.class,
                    () -> closed.get(1, TimeUnit.SECONDS)).getCause().getMessage());
            CompletableFuture<Review> closedReview = service.entityAsync(new Prompt("返回评价"), Review.class);
            assertEquals("AI 服务已关闭", assertThrows(ExecutionException.class,
                    () -> closedReview.get(1, TimeUnit.SECONDS)).getCause().getMessage());
            assertThrows(IllegalStateException.class, () -> service.stream(new Prompt("你好")).blockLast());
            assertTrue(requests.isEmpty());
        }

        /**
         * 验证默认不重试，错误状态只产生一次请求
         */
        @ParameterizedTest
        @EnumSource(AiProvider.class)
        void doesNotRetryFailedRequestsByDefault(AiProvider provider) {
            responseStatus = 429;
            StarBotAiService service = createService(provider);

            assertThrows(RuntimeException.class, () -> service.chat("你好"));

            assertEquals(1, requests.size());
        }

        /**
         * 验证各协议的超时配置能够终止等待
         */
        @ParameterizedTest
        @EnumSource(AiProvider.class)
        void appliesRequestTimeout(AiProvider provider) {
            properties.getAi().getModels().put("default", model(provider));
            properties.getAi().getModels().get("default").setTimeout(Duration.ofMillis(150));
            responseDelay = 700;
            StarBotAiService service = context.getBean(StarBotAiService.class);

            assertThrows(RuntimeException.class, () -> service.chat("你好"));
            assertEquals(1, requests.size());
        }

        /**
         * 配置指定协议的默认模型并获取 AI 服务
         * @param provider 接口协议
         * @return AI 服务
         */
        private StarBotAiService createService(AiProvider provider) {
            properties.getAi().getModels().put("default", model(provider));
            return context.getBean(StarBotAiService.class);
        }

        /**
         * 创建访问本机模拟接口的模型配置
         * @param provider 接口协议
         * @return 模型配置
         */
        private AiModel model(AiProvider provider) {
            AiModel config = new AiModel();
            config.setProvider(provider);
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway");
            config.setApiKey("test-key");
            config.setModel("test-model");
            config.setMaxTokens(64);
            config.setTimeout(Duration.ofSeconds(3));
            config.setHeaders(Map.of("X-Test", "test-value"));
            if (provider == AiProvider.OPENAI) {
                config.setExtraBody(Map.of("custom_parameter", "custom-value"));
            }
            return config;
        }

        /**
         * 记录请求并按协议返回模拟的普通、流式或错误回复
         * @param exchange HTTP 请求与响应对象
         * @throws IOException 读取请求或写入回复失败
         */
        private void handle(HttpExchange exchange) throws IOException {
            JSONObject body = JSONObject.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
            if (responseDelay > 0) {
                try {
                    Thread.sleep(responseDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean anthropic = exchange.getRequestURI().getPath().endsWith("/messages");
            boolean stream = body.getBooleanValue("stream");
            String content;
            if (responseStatus != 200) {
                content = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"local error\"}}";
            } else if (stream) {
                content = anthropic ? anthropicStream() : openAiStream();
            } else {
                String replyText = responseTexts.poll();
                if (replyText == null) {
                    replyText = responseText;
                }
                content = anthropic ? anthropicReply(replyText).toJSONString() : openAiReply(replyText).toJSONString();
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            try (exchange) {
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        /**
         * 构造 OpenAI 协议的模拟聊天回复
         * @param replyText 回复文本
         * @return 模拟回复 JSON
         */
        private JSONObject openAiReply(String replyText) {
            return JSONObject.of("id", "test-id", "object", "chat.completion", "created", 1718345013L,
                    "model", "test-model", "choices", JSONArray.of(JSONObject.of("index", 0,
                            "message", JSONObject.of("role", "assistant", "content", replyText), "finish_reason", "stop")),
                    "usage", JSONObject.of("prompt_tokens", 10, "completion_tokens", 2, "total_tokens", 12));
        }

        /**
         * 构造 Anthropic 协议的模拟聊天回复
         * @param replyText 回复文本
         * @return 模拟回复 JSON
         */
        private JSONObject anthropicReply(String replyText) {
            return JSONObject.of("id", "test-id", "type", "message", "role", "assistant", "model", "test-model",
                    "content", JSONArray.of(JSONObject.of("type", "text", "text", replyText)),
                    "stop_reason", "end_turn", "stop_sequence", null,
                    "usage", JSONObject.of("input_tokens", 10, "output_tokens", 2));
        }

        /**
         * 构造 OpenAI 协议的模拟 SSE 回复
         * @return SSE 数据
         */
        private String openAiStream() {
            JSONObject chunk = JSONObject.of("id", "test-id", "object", "chat.completion.chunk", "created", 1718345013L,
                    "model", "test-model", "choices", JSONArray.of(JSONObject.of("index", 0,
                            "delta", JSONObject.of("role", "assistant", "content", responseText), "finish_reason", null)));
            return "data: " + chunk.toJSONString() + "\n\ndata: [DONE]\n\n";
        }

        /**
         * 构造 Anthropic 协议的模拟 SSE 回复
         * @return SSE 数据
         */
        private String anthropicStream() {
            JSONObject start = anthropicReply(responseText);
            start.put("content", new JSONArray());
            start.put("stop_reason", null);
            return "event: message_start\ndata: " + JSONObject.of("type", "message_start", "message", start).toJSONString() + "\n\n"
                    + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                    + "event: content_block_delta\ndata: " + JSONObject.of("type", "content_block_delta", "index", 0,
                            "delta", JSONObject.of("type", "text_delta", "text", responseText)).toJSONString() + "\n\n"
                    + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                    + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}\n\n"
                    + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        }

        public record Review(String comment, int score) {
        }

        private record Request(String path, Map<String, List<String>> headers, JSONObject body) {
            /**
             * 按忽略大小写的名称获取请求头值
             * @param name 请求头名称
             * @return 请求头值，不存在时返回 null
             */
            private String header(String name) {
                return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
            }
        }
    }

    /**
     * 生成两种协议下缺失、空字符串及空白基础地址的测试参数
     * @return 协议与基础地址的参数组合
     */
    private static Stream<Arguments> missingAndBlankBaseUrls() {
        return Stream.of(AiProvider.values())
                .flatMap(provider -> Stream.of(null, "", " \t ").map(baseUrl -> Arguments.of(provider, baseUrl)));
    }
}
