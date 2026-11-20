package com.starlwr.bot.core.config.action;

import com.alibaba.fastjson2.JSONObject;

/**
 * 配置中心动作处理器
 */
public interface ConfigActionHandler {
    /**
     * 执行动作方法
     * @param parameters 动作参数
     * @return 执行成功提示
     */
    String execute(JSONObject parameters) throws Exception;
}
