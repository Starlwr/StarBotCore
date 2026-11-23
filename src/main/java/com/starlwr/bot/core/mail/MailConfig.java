package com.starlwr.bot.core.mail;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * 邮件自动配置
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "starbot.core.mail.enabled", havingValue = "false", matchIfMissing = true)
@ImportAutoConfiguration(exclude = MailSenderAutoConfiguration.class)
public class MailConfig {
}
