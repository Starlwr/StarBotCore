package com.starlwr.bot.core.config.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 配置中心控制器
 */
@RestController
@ConditionalOnBean(ConfigWebEndpoint.class)
public class ConfigController {
    /**
     * 配置中心请求处理器
     */
    private final ConfigWebEndpoint endpoint;

    public ConfigController(ConfigWebEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * 将请求转发配置中心处理
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @RequestMapping({"/config", "/config/**"})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        ConfigWebEndpoint.Response result = endpoint.handle(request.getMethod(), path, request.getHeader("Authorization"),
                request.getHeader("Origin"), request.getHeader("Host"), request::getInputStream);
        response.setStatus(result.getStatus());
        response.setContentType(result.getContentType());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        result.getHeaders().forEach(response::setHeader);
        response.getOutputStream().write(result.getBody());
    }
}
