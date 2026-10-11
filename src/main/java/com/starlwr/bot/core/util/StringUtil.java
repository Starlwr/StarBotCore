package com.starlwr.bot.core.util;

import org.apache.commons.lang3.StringUtils;

/**
 * 字符串工具类
 */
public class StringUtil {
    /**
     * 获取截断后的字符串，如果字符串长度超过指定长度，则在末尾添加省略号
     * @param str 要截断的字符串
     * @param maxLength 最大内容长度，不含省略号，非正数表示不限制长度
     * @return 截断后的字符串
     */
    public static String getOmitString(String str, int maxLength) {
        if (StringUtils.isEmpty(str) || maxLength <= 0 || str.length() <= maxLength) {
            return str;
        }

        return StringUtils.truncate(str, maxLength) + "...";
    }
}
