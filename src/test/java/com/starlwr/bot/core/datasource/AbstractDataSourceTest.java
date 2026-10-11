package com.starlwr.bot.core.datasource;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.datasource.base.StarBotDataSourceChangeEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceAddEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceRemoveEvent;
import com.starlwr.bot.core.event.datasource.change.StarBotDataSourceUpdateEvent;
import com.starlwr.bot.core.event.dynamic.StarBotBaseDynamicEvent;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.exception.DataSourceException;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.StarBotEventHandlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据源抽象类测试
 * <p>
 * 覆盖用户添加、更新、移除、查询、消息初始化和事件发布，外部平台及处理器服务均使用模拟依赖。
 */
class AbstractDataSourceTest {
    private JsonDataSource dataSource;

    private StarBotCoreProperties properties;

    private DataSourceService service;

    private DataSourceServiceRegistry registry;

    private StarBotEventHandlerService handlerService;

    private StarBotEventHandler liveHandler;

    private StarBotEventHandler dynamicHandler;

    private List<StarBotDataSourceChangeEvent> events;

    private Consumer<StarBotDataSourceChangeEvent> eventListener;

    private List<StarBotDataSourceRemoveEvent> removeEvents;

    @BeforeEach
    void setUp() {
        service = mock(DataSourceService.class);
        registry = mock(DataSourceServiceRegistry.class);
        when(registry.getDataSourceService(anyString())).thenReturn(Optional.of(service));

        liveHandler = mock(StarBotEventHandler.class);
        when(liveHandler.getEventType()).thenAnswer(invocation -> StarBotBaseLiveEvent.class);
        when(liveHandler.getDefaultParams()).thenAnswer(invocation -> new JSONObject());
        dynamicHandler = mock(StarBotEventHandler.class);
        when(dynamicHandler.getEventType()).thenAnswer(invocation -> StarBotBaseDynamicEvent.class);
        when(dynamicHandler.getDefaultParams()).thenAnswer(invocation -> new JSONObject());
        handlerService = mock(StarBotEventHandlerService.class);
        when(handlerService.getHandler("test.live")).thenReturn(Optional.of(liveHandler));
        when(handlerService.getHandler("test.dynamic")).thenReturn(Optional.of(dynamicHandler));

        removeEvents = new ArrayList<>();
        events = new ArrayList<>();
        eventListener = event -> { };
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof StarBotDataSourceChangeEvent changeEvent) {
                events.add(changeEvent);
                if (changeEvent instanceof StarBotDataSourceRemoveEvent removeEvent) {
                    removeEvents.add(removeEvent);
                }
                eventListener.accept(changeEvent);
            }
        };
        properties = new StarBotCoreProperties();
        dataSource = new JsonDataSource(publisher, registry, handlerService, properties);
    }

    /**
     * 验证重载时分别处理新增、移除和更新，并使用新配置中的用户对象
     */
    @Test
    void reloadUsesNewConfigurationForExistingUsers(@TempDir Path directory) throws IOException {
        PushUser original = addUser("bilibili", 1001L);
        PushUser removed = addUser("bilibili", 1002L);
        Path path = directory.resolve("datasource.json");
        Files.writeString(path, """
                [{
                    "uid": 1001,
                    "platform": "bilibili",
                    "targets": [{
                        "platform": "test-bot",
                        "type": 1,
                        "num": 2002,
                        "messages": [{"handler": "test.live"}]
                    }]
                }, {
                    "uid": 1003,
                    "platform": "bilibili"
                }]
                """);
        properties.getDatasource().setJsonPath(path.toString());
        clearEvents();

        ReflectionTestUtils.invokeMethod(dataSource, "reload");

        PushUser updated = dataSource.getUser("bilibili", 1001L).orElseThrow();
        assertNotSame(original, updated);
        assertEquals(2002L, updated.getTargets().get(0).getNum());
        assertEquals(2, dataSource.getAllUsers().size());
        assertTrue(dataSource.getUser("bilibili", 1002L).isEmpty());
        assertTrue(dataSource.getUser("bilibili", 1003L).isPresent());
        assertEquals(1, eventsOfType(StarBotDataSourceAddEvent.class).size());
        assertEquals(1, eventsOfType(StarBotDataSourceUpdateEvent.class).size());
        assertEquals(1, removeEvents.size());
        assertSame(removed, removeEvents.get(0).getUser());
    }

    /**
     * 验证单用户添加入口能够正常初始化并保存用户
     */
    @Test
    void addSingleUserInitializesAndStoresUser() {
        PushUser user = parseUser("bilibili", 1001L, true);

        dataSource.add(user);

        assertSame(user, dataSource.getUser("bilibili", 1001L).orElseThrow());
        assertTrue(user.hasEnabledLiveEvent());
    }

    /**
     * 验证更新后的用户在所有查询入口保持一致
     */
    @Test
    void updateKeepsAllQueriesConsistent() {
        addUser("bilibili", 1001L);
        PushUser updated = parseUser("bilibili", 1001L, true);
        updated.setUname("更新后的主播");

        dataSource.update(updated);

        assertSame(updated, dataSource.getUser("bilibili", 1001L).orElseThrow());
        assertSame(updated, dataSource.getUsers("bilibili").get(0));
        assertSame(updated, dataSource.getAllUsers().get(0));
    }

    /**
     * 验证仅提供平台和 UID 时仍发布完整的原用户
     */
    @Test
    void removeUsesStoredUserWhenOnlyIdentityIsProvided() {
        PushUser original = addUser("bilibili", 1001L);
        PushUser identity = new PushUser();
        identity.setPlatform("bilibili");
        identity.setUid(1001L);

        dataSource.remove(identity);

        assertRemoved(original);
        assertEquals(3001L, removeEvents.get(0).getUser().getRoomId());
    }

    /**
     * 验证 JSON 中禁用已有用户时使用已初始化的原对象，避免直播事件检查空指针
     */
    @Test
    void disablingParsedUserPublishesInitializedStoredUser() {
        PushUser original = addUser("bilibili", 1001L);
        PushUser disabled = parseUser("bilibili", 1001L, false);
        assertNull(disabled.getTargets().get(0).getMessages().get(0).getEventClass());
        eventListener = event -> assertTrue(event.getUser().hasEnabledLiveEvent(), "删除事件应保留用户原有的直播事件类型");

        dataSource.update(disabled);

        assertRemoved(original);
        assertTrue(removeEvents.get(0).getUser().getEnabled());
    }

    /**
     * 验证不同平台使用相同 UID 时只移除指定平台的用户
     */
    @Test
    void removeKeepsUserWithSameUidOnAnotherPlatform() {
        PushUser original = addUser("bilibili", 1001L);
        PushUser other = addUser("other", 1001L);

        dataSource.remove(parseUser("bilibili", 1001L, false));

        assertRemoved(original);
        assertSame(other, dataSource.getUser("other", 1001L).orElseThrow());
        assertEquals(List.of(other), dataSource.getAllUsers());
    }

    /**
     * 验证移除已缓存用户不依赖平台接口重新补全信息
     */
    @Test
    void removeDoesNotRequireRefreshingUserInfo() {
        PushUser original = addUser("bilibili", 1001L);
        doThrow(new IllegalStateException("平台接口不可用")).when(service).completePushUser(any(PushUser.class));

        dataSource.remove(parseUser("bilibili", 1001L, false));

        assertRemoved(original);
    }

    /**
     * 验证平台或 UID 不存在时保持原有异常行为，不移除其他用户
     */
    @ParameterizedTest
    @CsvSource({"other, 1001", "bilibili, 1002"})
    void removeRejectsUnknownUser(String platform, long uid) {
        PushUser original = addUser("bilibili", 1001L);

        assertThrows(DataSourceException.class, () -> dataSource.remove(parseUser(platform, uid, false)));

        assertSame(original, dataSource.getUser("bilibili", 1001L).orElseThrow());
        assertEquals(List.of(original), dataSource.getAllUsers());
        assertTrue(removeEvents.isEmpty());
    }

    /**
     * 用户添加及平台信息补全
     */
    @Nested
    class AddTests {
        /**
         * 验证单用户入口忽略禁用用户，不调用平台和处理器服务
         */
        @Test
        void singleDisabledUserIsIgnored() {
            dataSource.add(parseUser("bilibili", 1001L, false));

            assertTrue(dataSource.getAllUsers().isEmpty());
            assertTrue(events.isEmpty());
            verifyNoInteractions(registry, service, handlerService);
        }

        /**
         * 验证批量添加过滤禁用用户、目标和消息，保留有效配置
         */
        @Test
        void batchAddFiltersDisabledUsersTargetsAndMessages() {
            PushUser enabled = configuredUser("bilibili", 1001L);
            PushUser disabled = parseUser("bilibili", 1001L, false);
            PushTarget target = enabled.getTargets().get(0);
            addMessage(target, "test.dynamic", false, null);
            PushTarget disabledTarget = addTarget(enabled, 2002L, false);
            addMessage(disabledTarget, "test.live", true, null);

            dataSource.add(new ArrayList<>(List.of(enabled, disabled)));

            assertStoredUsers(enabled);
            assertEquals(List.of(target), enabled.getTargets());
            assertEquals(1, target.getMessages().size());
            assertSame(liveHandler, firstMessage(enabled).getHandlerInstance());
            assertEquals(1, eventsOfType(StarBotDataSourceAddEvent.class).size());
        }

        /**
         * 验证不同平台分别批量补全用户，并为每个用户发布添加事件
         */
        @Test
        void batchAddCompletesUsersByPlatformAndPublishesEachUser() {
            var otherService = mock(DataSourceService.class);
            when(registry.getDataSourceService("other")).thenReturn(Optional.of(otherService));
            PushUser first = configuredUser("bilibili", 1001L);
            PushUser second = configuredUser("bilibili", 1002L);
            PushUser other = configuredUser("other", 1001L);
            doAnswer(invocation -> {
                List<PushUser> users = invocation.getArgument(0);
                users.forEach(user -> user.setUname("补全后的主播"));
                return null;
            }).when(service).completePushUsers(anyList());

            dataSource.add(new ArrayList<>(List.of(first, other, second)));

            verify(service).completePushUsers(List.of(first, second));
            verify(otherService).completePushUsers(List.of(other));
            assertStoredUsers(first, other, second);
            assertEquals("补全后的主播", first.getUname());
            assertEquals("补全后的主播", second.getUname());
            assertEquals(List.of(first, other, second), events.stream().map(StarBotDataSourceChangeEvent::getUser).toList());
            assertEquals(3, eventsOfType(StarBotDataSourceAddEvent.class).size());
        }

        /**
         * 验证批次内同平台同 UID 重复时拒绝整个批次
         */
        @Test
        void duplicateUsersInBatchAreRejectedBeforeAnyWrites() {
            PushUser first = configuredUser("bilibili", 1001L);
            PushUser duplicate = configuredUser("bilibili", 1001L);

            assertThrows(DataSourceException.class,
                    () -> dataSource.add(new ArrayList<>(List.of(first, duplicate))));

            assertTrue(dataSource.getAllUsers().isEmpty());
            assertTrue(dataSource.getUser("bilibili", 1001L).isEmpty());
            assertTrue(events.isEmpty());
            verifyNoInteractions(service, handlerService);
        }

        /**
         * 验证批次中包含已有用户时不会部分添加前面的新用户
         */
        @Test
        void existingUserInBatchPreventsPartialAdds() {
            PushUser original = addUser("bilibili", 1001L);
            PushUser added = configuredUser("bilibili", 1002L);
            clearEvents();
            clearInvocations(service, handlerService);

            assertThrows(DataSourceException.class, () -> dataSource.add(new ArrayList<>(
                    List.of(added, configuredUser("bilibili", 1001L)))));

            assertStoredUsers(original);
            assertTrue(dataSource.getUser("bilibili", 1002L).isEmpty());
            assertTrue(events.isEmpty());
            verifyNoInteractions(service, handlerService);
        }

        /**
         * 验证不支持的平台被忽略，其他平台仍然正常添加
         */
        @Test
        void unsupportedPlatformsAreSkippedWithoutAffectingSupportedUsers() {
            when(registry.getDataSourceService("unsupported")).thenReturn(Optional.empty());
            PushUser supported = configuredUser("bilibili", 1001L);
            PushUser unsupported = configuredUser("unsupported", 1002L);

            dataSource.add(new ArrayList<>(List.of(unsupported, supported)));

            assertStoredUsers(supported);
            assertTrue(dataSource.getUser("unsupported", 1002L).isEmpty());
            assertEquals(1, events.size());
            assertSame(supported, events.get(0).getUser());
        }

        /**
         * 验证平台信息补全失败时不保存用户或发布添加事件
         */
        @Test
        void completionFailureLeavesDataSourceUnchanged() {
            var failure = new IllegalStateException("平台接口不可用");
            doThrow(failure).when(service).completePushUsers(anyList());
            PushUser user = configuredUser("bilibili", 1001L);

            assertSame(failure, assertThrows(IllegalStateException.class, () -> dataSource.add(user)));

            assertTrue(dataSource.getAllUsers().isEmpty());
            assertTrue(dataSource.getUser("bilibili", 1001L).isEmpty());
            assertTrue(events.isEmpty());
            verifyNoInteractions(handlerService);
        }

        /**
         * 验证监听器收到添加事件时能够查询已初始化的用户
         */
        @Test
        void addEventObservesInitializedStoredUser() {
            PushUser user = configuredUser("bilibili", 1001L);
            eventListener = event -> {
                assertInstanceOf(StarBotDataSourceAddEvent.class, event);
                assertSame(user, event.getUser());
                assertStoredUsers(user);
                assertSame(liveHandler, firstMessage(user).getHandlerInstance());
                assertEquals(StarBotBaseLiveEvent.class, firstMessage(user).getEventClass());
                assertNotNull(firstMessage(user).getParamsJsonObject());
            };

            dataSource.add(user);

            assertEquals(1, events.size());
        }

        /**
         * 验证空添加批次不会调用外部服务或发布事件
         */
        @Test
        void emptyBatchIsIgnored() {
            dataSource.add(new ArrayList<>());

            assertTrue(dataSource.getAllUsers().isEmpty());
            assertTrue(events.isEmpty());
            verifyNoInteractions(registry, service, handlerService);
        }
    }

    /**
     * 用户更新及配置差异处理
     */
    @Nested
    class UpdateTests {
        /**
         * 验证更新入口会添加不存在的启用用户
         */
        @Test
        void unknownEnabledUserIsAdded() {
            PushUser user = configuredUser("bilibili", 1001L);

            dataSource.update(user);

            assertStoredUsers(user);
            assertEquals(1, eventsOfType(StarBotDataSourceAddEvent.class).size());
            assertTrue(eventsOfType(StarBotDataSourceUpdateEvent.class).isEmpty());
            assertTrue(user.hasEnabledLiveEvent());
        }

        /**
         * 验证不存在的禁用用户被忽略，不影响已有用户
         */
        @Test
        void unknownDisabledUserIsIgnored() {
            PushUser original = addUser("bilibili", 1001L);
            clearEvents();
            clearInvocations(registry, service, handlerService);

            dataSource.update(parseUser("bilibili", 1002L, false));

            assertStoredUsers(original);
            assertTrue(dataSource.getUser("bilibili", 1002L).isEmpty());
            assertTrue(events.isEmpty());
            verifyNoInteractions(registry, service, handlerService);
        }

        /**
         * 验证配置未变化时保留原对象且不发布更新事件
         */
        @Test
        void unchangedUserKeepsOriginalWithoutUpdateEvent() {
            PushUser original = addUser("bilibili", 1001L);
            clearEvents();
            clearInvocations(handlerService);

            dataSource.update(configuredUser("bilibili", 1001L));

            assertStoredUsers(original);
            assertTrue(events.isEmpty());
            verifyNoInteractions(handlerService);
        }

        /**
         * 验证事件保留旧对象，并在发布前完成新对象的消息初始化及查询替换
         */
        @Test
        void updateEventObservesOldAndInitializedNewUser() {
            PushUser original = addUser("bilibili", 1001L);
            PushUser updated = configuredUser("bilibili", 1001L);
            firstMessage(updated).setHandler("test.dynamic");
            clearEvents();
            eventListener = event -> {
                assertInstanceOf(StarBotDataSourceUpdateEvent.class, event);
                StarBotDataSourceUpdateEvent updateEvent = (StarBotDataSourceUpdateEvent) event;
                assertSame(original, updateEvent.getOldUser());
                assertSame(updated, updateEvent.getUser());
                assertTrue(original.hasEnabledLiveEvent());
                assertTrue(updated.hasEnabledDynamicEvent());
                assertFalse(updated.hasEnabledLiveEvent());
                assertSame(dynamicHandler, firstMessage(updated).getHandlerInstance());
                assertStoredUsers(updated);
            };

            dataSource.update(updated);

            assertEquals(1, events.size());
            assertEquals(StarBotBaseLiveEvent.class, firstMessage(original).getEventClass());
        }

        /**
         * 验证更新时过滤禁用目标和消息，并初始化保留的消息
         */
        @Test
        void updateFiltersDisabledTargetsAndMessages() {
            PushUser original = addUser("bilibili", 1001L);
            PushUser updated = configuredUser("bilibili", 1001L);
            PushTarget target = updated.getTargets().get(0);
            firstMessage(updated).setEnabled(false);
            PushMessage dynamic = addMessage(target, "test.dynamic", true, "{\"limit\":5}");
            PushTarget disabledTarget = addTarget(updated, 2002L, false);
            addMessage(disabledTarget, "test.live", true, null);
            clearEvents();

            dataSource.update(updated);

            assertStoredUsers(updated);
            assertEquals(List.of(target), updated.getTargets());
            assertEquals(List.of(dynamic), target.getMessages());
            assertSame(dynamicHandler, dynamic.getHandlerInstance());
            assertEquals(5, dynamic.getParamsJsonObject().getIntValue("limit"));
            assertEquals(1, eventsOfType(StarBotDataSourceUpdateEvent.class).size());
            assertTrue(original.hasEnabledLiveEvent());
        }

        /**
         * 验证同批次新增、禁用、更新和忽略不存在的禁用用户时状态与事件均正确
         */
        @Test
        void mixedBatchAddsRemovesUpdatesAndIgnoresUnknownDisabledUser() {
            PushUser original = addUser("bilibili", 1001L);
            PushUser removed = addUser("bilibili", 1002L);
            PushUser updated = configuredUser("bilibili", 1001L);
            firstMessage(updated).setParams("{\"limit\":5}");
            PushUser added = configuredUser("other", 1003L);
            clearEvents();

            dataSource.update(List.of(updated, parseUser("bilibili", 1002L, false), added,
                    parseUser("other", 1004L, false)));

            assertStoredUsers(updated, added);
            assertTrue(dataSource.getUser("bilibili", 1002L).isEmpty());
            assertTrue(dataSource.getUser("other", 1004L).isEmpty());
            assertEquals(3, events.size());
            assertSame(added, eventsOfType(StarBotDataSourceAddEvent.class).get(0).getUser());
            assertSame(removed, eventsOfType(StarBotDataSourceRemoveEvent.class).get(0).getUser());
            assertSame(original, eventsOfType(StarBotDataSourceUpdateEvent.class).get(0).getOldUser());
            assertSame(updated, eventsOfType(StarBotDataSourceUpdateEvent.class).get(0).getUser());
        }

        /**
         * 验证已有用户的平台服务不可用时保留旧配置
         */
        @Test
        void unsupportedPlatformKeepsStoredUser() {
            PushUser original = addUser("bilibili", 1001L);
            PushUser updated = configuredUser("bilibili", 1001L);
            firstMessage(updated).setParams("{\"limit\":5}");
            when(registry.getDataSourceService("bilibili")).thenReturn(Optional.empty());
            clearEvents();

            dataSource.update(updated);

            assertStoredUsers(original);
            assertTrue(events.isEmpty());
        }

        /**
         * 验证平台补全失败时保留已有用户且不发布更新事件
         */
        @Test
        void completionFailureKeepsStoredUser() {
            PushUser original = addUser("bilibili", 1001L);
            PushUser updated = configuredUser("bilibili", 1001L);
            firstMessage(updated).setParams("{\"limit\":5}");
            var failure = new IllegalStateException("平台接口不可用");
            doThrow(failure).when(service).completePushUsers(anyList());
            clearEvents();

            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> dataSource.update(updated)));

            assertStoredUsers(original);
            assertTrue(events.isEmpty());
        }

        /**
         * 验证昵称、头像及直播间变化均会触发更新，并保持旧对象的数据
         */
        @ParameterizedTest
        @ValueSource(strings = {"uname", "face", "roomId"})
        void metadataChangesPublishUpdate(String field) {
            PushUser original = addUser("bilibili", 1001L);
            PushUser updated = configuredUser("bilibili", 1001L);
            doAnswer(invocation -> {
                switch (field) {
                    case "uname" -> updated.setUname("新昵称");
                    case "face" -> updated.setFace("https://example.com/new-avatar.png");
                    case "roomId" -> updated.setRoomId(4001L);
                    default -> throw new IllegalArgumentException(field);
                }
                return null;
            }).when(service).completePushUsers(anyList());
            clearEvents();

            dataSource.update(updated);

            assertStoredUsers(updated);
            assertEquals(1, eventsOfType(StarBotDataSourceUpdateEvent.class).size());
            assertSame(original, eventsOfType(StarBotDataSourceUpdateEvent.class).get(0).getOldUser());
            assertEquals("测试主播", original.getUname());
            assertEquals(3001L, original.getRoomId());
            assertNull(original.getFace());
        }

        /**
         * 验证连续更新保持用户顺序，所有查询均返回最新对象，随后仍能正常移除
         */
        @Test
        void repeatedUpdatesPreserveOrderAndLatestState() {
            PushUser first = addUser("bilibili", 1001L);
            addUser("bilibili", 1002L);
            PushUser third = addUser("bilibili", 1003L);
            PushUser updated = configuredUser("bilibili", 1002L);
            firstMessage(updated).setParams("{\"limit\":5}");
            dataSource.update(updated);
            PushUser latest = configuredUser("bilibili", 1002L);
            firstMessage(latest).setParams("{\"limit\":10}");

            dataSource.update(latest);

            assertStoredUsers(first, latest, third);
            clearEvents();
            dataSource.remove(parseUser("bilibili", 1002L, false));
            assertStoredUsers(first, third);
            assertSame(latest, removeEvents.get(0).getUser());
        }

        /**
         * 验证空更新批次不修改数据或调用外部服务
         */
        @Test
        void emptyBatchIsIgnored() {
            PushUser original = addUser("bilibili", 1001L);
            clearEvents();
            clearInvocations(registry, service, handlerService);

            dataSource.update(List.of());

            assertStoredUsers(original);
            assertTrue(events.isEmpty());
            verifyNoInteractions(registry, service, handlerService);
        }
    }

    /**
     * 消息处理器及推送参数初始化
     */
    @Nested
    class MessageInitializationTests {
        /**
         * 验证自定义参数覆盖同名默认值，保留其他默认值并增加新参数
         */
        @Test
        void customParamsOverrideDefaultsAndKeepOtherValues() {
            useDefaultParams();
            PushUser user = configuredUser("bilibili", 1001L);
            firstMessage(user).setParams("{\"limit\":5,\"custom\":true,\"nullable\":null}");

            dataSource.add(user);

            JSONObject params = firstMessage(user).getParamsJsonObject();
            assertEquals(5, params.getIntValue("limit"));
            assertEquals("default", params.getString("format"));
            assertTrue(params.getBooleanValue("custom"));
            assertTrue(params.containsKey("nullable"));
            assertNull(params.get("nullable"));
            assertSame(liveHandler, firstMessage(user).getHandlerInstance());
            assertEquals(StarBotBaseLiveEvent.class, firstMessage(user).getEventClass());
        }

        /**
         * 验证未填写或仅有空白的参数使用默认值
         */
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t\r\n"})
        void missingOrBlankParamsUseDefaults(String params) {
            useDefaultParams();
            PushUser user = configuredUser("bilibili", 1001L);
            firstMessage(user).setParams(params);

            dataSource.add(user);

            assertEquals(10, firstMessage(user).getParamsJsonObject().getIntValue("limit"));
            assertEquals("default", firstMessage(user).getParamsJsonObject().getString("format"));
        }

        /**
         * 验证错误格式或非对象参数不会中断添加及后续消息初始化
         */
        @ParameterizedTest
        @ValueSource(strings = {"{", "[]", "null", "123"})
        void invalidParamsKeepDefaultsAndContinueInitializingOtherMessages(String params) {
            useDefaultParams();
            PushUser user = configuredUser("bilibili", 1001L);
            firstMessage(user).setParams(params);
            PushMessage dynamic = addMessage(user.getTargets().get(0), "test.dynamic", true, "{\"limit\":5}");

            dataSource.add(user);

            assertStoredUsers(user);
            assertEquals(10, firstMessage(user).getParamsJsonObject().getIntValue("limit"));
            assertSame(liveHandler, firstMessage(user).getHandlerInstance());
            assertSame(dynamicHandler, dynamic.getHandlerInstance());
            assertEquals(5, dynamic.getParamsJsonObject().getIntValue("limit"));
            assertEquals(1, eventsOfType(StarBotDataSourceAddEvent.class).size());
        }

        /**
         * 验证未知处理器的消息被移除且旧运行时参数被清理，其他消息正常保留
         */
        @Test
        void unknownHandlerIsRemovedAndStaleRuntimeFieldsAreCleared() {
            PushUser user = configuredUser("bilibili", 1001L);
            PushTarget target = user.getTargets().get(0);
            PushMessage invalid = addMessage(target, "test.unknown", true, null);
            invalid.setHandlerInstance(liveHandler);
            invalid.setEventClass(StarBotBaseLiveEvent.class);
            invalid.setParamsJsonObject(new JSONObject());
            PushMessage dynamic = addMessage(target, "test.dynamic", true, null);

            dataSource.add(user);

            assertStoredUsers(user);
            assertEquals(2, target.getMessages().size());
            assertFalse(target.getMessages().contains(invalid));
            assertNull(invalid.getHandlerInstance());
            assertNull(invalid.getEventClass());
            assertNull(invalid.getParamsJsonObject());
            assertTrue(user.hasEnabledLiveEvent());
            assertTrue(user.hasEnabledDynamicEvent());
            assertSame(dynamicHandler, dynamic.getHandlerInstance());
        }

        /**
         * 验证每条消息获取各自的默认参数，自定义值不会污染其他消息
         */
        @Test
        void messagesKeepIndependentParams() {
            useDefaultParams();
            PushUser first = configuredUser("bilibili", 1001L);
            firstMessage(first).setParams("{\"limit\":5}");
            PushUser second = configuredUser("bilibili", 1002L);

            dataSource.add(new ArrayList<>(List.of(first, second)));

            JSONObject firstParams = firstMessage(first).getParamsJsonObject();
            JSONObject secondParams = firstMessage(second).getParamsJsonObject();
            assertNotSame(firstParams, secondParams);
            assertEquals(5, firstParams.getIntValue("limit"));
            assertEquals(10, secondParams.getIntValue("limit"));
            firstParams.put("format", "changed");
            assertEquals("default", secondParams.getString("format"));
        }

        /**
         * 验证没有推送目标或消息的用户仍可保存并发布事件
         */
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void emptyTargetsOrMessagesAreAllowed(boolean emptyTargets) {
            PushUser user = configuredUser("bilibili", 1001L);
            if (emptyTargets) {
                user.getTargets().clear();
            } else {
                user.getTargets().get(0).getMessages().clear();
            }

            dataSource.add(user);

            assertStoredUsers(user);
            assertFalse(user.hasEnabledLiveEvent());
            assertFalse(user.hasEnabledDynamicEvent());
            assertEquals(1, events.size());
            verifyNoInteractions(handlerService);
        }

        /**
         * 验证更新时同样移除无效处理器，避免保留未初始化的消息
         */
        @Test
        void updateRemovesUnknownHandlerAndKeepsValidMessages() {
            addUser("bilibili", 1001L);
            PushUser updated = configuredUser("bilibili", 1001L);
            firstMessage(updated).setParams("{\"limit\":5}");
            PushMessage invalid = addMessage(updated.getTargets().get(0), "test.unknown", true, null);
            clearEvents();

            dataSource.update(updated);

            assertStoredUsers(updated);
            assertEquals(1, updated.getTargets().get(0).getMessages().size());
            assertFalse(updated.getTargets().get(0).getMessages().contains(invalid));
            assertTrue(updated.hasEnabledLiveEvent());
            assertEquals(5, firstMessage(updated).getParamsJsonObject().getIntValue("limit"));
            assertEquals(1, eventsOfType(StarBotDataSourceUpdateEvent.class).size());
        }
    }

    /**
     * 查询结果及列表隔离
     */
    @Nested
    class QueryTests {
        /**
         * 验证空数据源和不存在的平台、UID 返回空结果
         */
        @Test
        void emptyDataSourceReturnsEmptyResults() {
            assertTrue(dataSource.getAllUsers().isEmpty());
            assertTrue(dataSource.getUsers("missing").isEmpty());
            assertTrue(dataSource.getUser("missing", 1001L).isEmpty());
        }

        /**
         * 验证查询按平台和 UID 区分用户，未知 UID 不影响其他结果
         */
        @Test
        void queriesDistinguishPlatformAndUid() {
            PushUser first = addUser("bilibili", 1001L);
            PushUser second = addUser("bilibili", 1002L);
            PushUser other = addUser("other", 1001L);

            assertStoredUsers(first, second, other);
            List<PushUser> bilibiliUsers = dataSource.getUsers("bilibili");
            assertEquals(2, bilibiliUsers.size());
            assertTrue(bilibiliUsers.containsAll(List.of(first, second)));
            assertEquals(List.of(other), dataSource.getUsers("other"));
            assertSame(first, dataSource.getUser("bilibili", 1001L).orElseThrow());
            assertSame(other, dataSource.getUser("other", 1001L).orElseThrow());
            assertTrue(dataSource.getUser("bilibili", 9999L).isEmpty());
            assertTrue(dataSource.getUsers("missing").isEmpty());
        }

        /**
         * 验证修改返回列表的结构不会改变内部缓存，包括不存在平台的空列表
         */
        @ParameterizedTest
        @ValueSource(strings = {"all", "bilibili", "missing"})
        void changingReturnedListDoesNotMutateStoredUsers(String query) {
            PushUser original = addUser("bilibili", 1001L);
            List<PushUser> snapshot = "all".equals(query)
                    ? dataSource.getAllUsers() : dataSource.getUsers(query);

            snapshot.clear();
            snapshot.add(configuredUser("other", 1002L));

            assertStoredUsers(original);
            assertTrue(dataSource.getUser("other", 1002L).isEmpty());
            assertTrue(dataSource.getUsers("missing").isEmpty());
        }
    }

    /**
     * 用户移除的缓存边界及事件状态
     */
    @Nested
    class RemoveBoundaryTests {
        /**
         * 验证同平台有多个用户时只移除指定 UID
         */
        @Test
        void removingOneUserKeepsOthersOnSamePlatform() {
            PushUser removed = addUser("bilibili", 1001L);
            PushUser retained = addUser("bilibili", 1002L);
            clearEvents();

            dataSource.remove(parseUser("bilibili", 1001L, false));

            assertRemoved(removed);
            assertStoredUsers(retained);
            assertEquals(List.of(retained), dataSource.getUsers("bilibili"));
        }

        /**
         * 验证最后一个用户移除后可重新添加同平台同 UID 用户
         */
        @Test
        void removedLastUserCanBeAddedAgain() {
            PushUser original = addUser("bilibili", 1001L);
            dataSource.remove(original);
            assertTrue(dataSource.getUsers("bilibili").isEmpty());
            PushUser replacement = configuredUser("bilibili", 1001L);

            dataSource.add(replacement);

            assertStoredUsers(replacement);
            assertNotSame(original, replacement);
            assertEquals(2, eventsOfType(StarBotDataSourceAddEvent.class).size());
            assertEquals(1, eventsOfType(StarBotDataSourceRemoveEvent.class).size());
        }

        /**
         * 验证不包含直播事件的用户也可正常移除
         */
        @Test
        void userWithoutLiveEventsCanBeRemoved() {
            PushUser user = configuredUser("bilibili", 1001L);
            firstMessage(user).setHandler("test.dynamic");
            dataSource.add(user);
            clearEvents();

            dataSource.remove(parseUser("bilibili", 1001L, false));

            assertRemoved(user);
            assertFalse(removeEvents.get(0).getUser().hasEnabledLiveEvent());
            assertTrue(removeEvents.get(0).getUser().hasEnabledDynamicEvent());
        }

        /**
         * 验证删除事件发布前完成缓存移除，并向监听器提供原有初始化数据
         */
        @Test
        void removeEventObservesUserAlreadyRemovedFromQueries() {
            PushUser original = addUser("bilibili", 1001L);
            clearEvents();
            eventListener = event -> {
                assertInstanceOf(StarBotDataSourceRemoveEvent.class, event);
                assertSame(original, event.getUser());
                assertTrue(original.hasEnabledLiveEvent());
                assertStoredUsers();
                assertTrue(dataSource.getUsers("bilibili").isEmpty());
                assertTrue(dataSource.getUser("bilibili", 1001L).isEmpty());
            };

            dataSource.remove(parseUser("bilibili", 1001L, false));

            assertEquals(1, events.size());
        }
    }

    /**
     * 模拟直播处理器的默认推送参数，每次调用返回独立的 JSON 对象
     */
    private void useDefaultParams() {
        when(liveHandler.getDefaultParams()).thenAnswer(invocation -> {
            JSONObject params = new JSONObject();
            params.put("limit", 10);
            params.put("format", "default");
            return params;
        });
    }

    /**
     * 创建并添加已启用的测试用户，由数据源完成消息初始化
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 已添加并完成消息初始化的推送用户
     */
    private PushUser addUser(String platform, long uid) {
        PushUser user = configuredUser(platform, uid);
        dataSource.add(new ArrayList<>(List.of(user)));
        return user;
    }

    /**
     * 创建包含固定昵称、房间号和直播推送配置的启用用户
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 尚未由数据源初始化的推送用户
     */
    private PushUser configuredUser(String platform, long uid) {
        PushUser user = parseUser(platform, uid, true);
        user.setUname("测试主播");
        user.setRoomId(3001L);
        return user;
    }

    /**
     * 为测试用户添加群推送目标
     * @param user 推送用户
     * @param num 推送群号
     * @param enabled 是否启用
     * @return 新增的推送目标
     */
    private PushTarget addTarget(PushUser user, long num, boolean enabled) {
        PushTarget target = new PushTarget();
        target.setUser(user);
        target.setPlatform("test-bot");
        target.setType(PushTargetType.GROUP);
        target.setNum(num);
        target.setEnabled(enabled);
        user.getTargets().add(target);
        return target;
    }

    /**
     * 为测试推送目标添加消息配置
     * @param target 推送目标
     * @param handler 事件处理器名称
     * @param enabled 是否启用
     * @param params JSON 格式的自定义推送参数，可为 null
     * @return 新增的推送消息
     */
    private PushMessage addMessage(PushTarget target, String handler, boolean enabled, String params) {
        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setHandler(handler);
        message.setEnabled(enabled);
        message.setParams(params);
        target.getMessages().add(message);
        return message;
    }

    /**
     * 获取测试用户第一个推送目标中的第一条消息
     * @param user 推送用户
     * @return 第一条推送消息
     */
    private PushMessage firstMessage(PushUser user) {
        return user.getTargets().get(0).getMessages().get(0);
    }

    /**
     * 清空已记录的数据源变更事件和删除事件
     */
    private void clearEvents() {
        events.clear();
        removeEvents.clear();
    }

    /**
     * 从已记录的数据源变更事件中筛选指定类型
     * @param type 事件类型
     * @param <T> 数据源变更事件类型
     * @return 按记录顺序排列的匹配事件列表
     */
    private <T extends StarBotDataSourceChangeEvent> List<T> eventsOfType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * 验证用户数量和顺序，并检查各查询入口返回相同的用户对象
     * @param expectedUsers 按预期顺序排列的用户对象
     */
    private void assertStoredUsers(PushUser... expectedUsers) {
        List<PushUser> actualUsers = dataSource.getAllUsers();
        assertEquals(expectedUsers.length, actualUsers.size());
        for (int index = 0; index < expectedUsers.length; index++) {
            PushUser expected = expectedUsers[index];
            assertSame(expected, actualUsers.get(index));
            assertSame(expected, dataSource.getUser(expected.getPlatform(), expected.getUid()).orElseThrow());
            assertTrue(dataSource.getUsers(expected.getPlatform()).stream().anyMatch(user -> user == expected));
        }
    }

    /**
     * 通过 JSON 解析构造包含一个群推送目标和直播消息的测试用户
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param enabled 是否启用
     * @return 尚未初始化处理器和消息参数的推送用户
     */
    private PushUser parseUser(String platform, long uid, boolean enabled) {
        String json = """
                [{
                    "uid": %d,
                    "platform": "%s",
                    "enabled": %s,
                    "targets": [{
                        "platform": "test-bot",
                        "type": 1,
                        "num": 2001,
                        "messages": [{"handler": "test.live"}]
                    }]
                }]
                """.formatted(uid, platform, enabled);
        return dataSource.parse(json).get(0);
    }

    /**
     * 验证仅发布一次携带原用户的删除事件，且用户已从查询结果中移除
     * @param original 移除前的用户对象
     */
    private void assertRemoved(PushUser original) {
        assertEquals(1, removeEvents.size());
        assertSame(original, removeEvents.get(0).getUser());
        assertTrue(dataSource.getUser(original.getPlatform(), original.getUid()).isEmpty());
        assertFalse(dataSource.getAllUsers().contains(original));
    }
}
