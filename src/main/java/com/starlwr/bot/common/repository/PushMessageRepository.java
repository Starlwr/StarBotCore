package com.starlwr.bot.common.repository;

import com.starlwr.bot.common.model.PushMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 推送消息 JPA 接口
 */
@Profile("mysql")
@Repository
public interface PushMessageRepository extends JpaRepository<PushMessage, Long> {
}
