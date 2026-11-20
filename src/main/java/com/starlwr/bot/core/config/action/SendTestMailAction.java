package com.starlwr.bot.core.config.action;

import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * 测试邮件发送动作处理器
 */
public class SendTestMailAction implements ConfigActionHandler {
    @Override
    public String execute(JSONObject parameters) throws Exception {
        // 检查页面中的邮件开关和默认收件人
        JSONObject mail = parameters.getJSONObject("mail");
        if (!mail.getBooleanValue("enabled")) {
            throw new IllegalArgumentException("请先在页面中开启邮件功能");
        }
        String receiver = mail.getString("default-to");
        if (StringUtils.isBlank(receiver)) {
            throw new IllegalArgumentException("请填写默认收件邮箱");
        }

        // 绑定 Spring 邮件属性并检查 SMTP 主机
        MailProperties properties = new Binder(new MapConfigurationPropertySource(parameters.getJSONObject("smtp")))
                .bind("spring.mail", MailProperties.class).orElseGet(MailProperties::new);
        if (StringUtils.isBlank(properties.getHost())) {
            throw new IllegalArgumentException("请填写 SMTP 主机");
        }

        // 根据页面填写的服务器配置创建临时邮件发送器
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        if (properties.getPort() != null) {
            sender.setPort(properties.getPort());
        }
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        sender.setProtocol(properties.getProtocol());
        sender.setDefaultEncoding(properties.getDefaultEncoding().name());

        // 设置默认超时，使用传入的邮件属性覆盖默认值
        Properties settings = new Properties();
        String prefix = "mail." + properties.getProtocol() + ".";
        settings.put(prefix + "connectiontimeout", "10000");
        settings.put(prefix + "timeout", "10000");
        settings.put(prefix + "writetimeout", "10000");
        settings.putAll(properties.getProperties());
        sender.setJavaMailProperties(settings);

        // 生成测试邮件，发送给填写的收件人
        SimpleMailMessage message = new SimpleMailMessage();
        if (StringUtils.isNotBlank(properties.getUsername())) {
            message.setFrom(properties.getUsername());
        }
        message.setTo(receiver);
        message.setSubject("StarBot 测试邮件");
        message.setText("这是一封 StarBot 配置中心发送的测试邮件。收到此邮件说明当前页面填写的邮件配置可以正常发送邮件。\n\n本次测试不会保存配置，请在确认后点击“保存配置”。");
        sender.send(message);
        return "测试邮件已发送, 请查看收件箱";
    }

}
