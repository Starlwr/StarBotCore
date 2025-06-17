package com.starlwr.bot.common.plugin;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Objects;

/**
 * StarBot 插件信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class StarBotPluginMeta {
    /**
     * 插件名称
     */
    private String name;

    /**
     * 插件版本
     */
    private String version;

    /**
     * 插件作者
     */
    private String author;

    /**
     * 插件描述
     */
    private String description;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StarBotPluginMeta that)) return false;
        return Objects.equals(name, that.name) && Objects.equals(version, that.version) && Objects.equals(author, that.author);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version, author);
    }
}
