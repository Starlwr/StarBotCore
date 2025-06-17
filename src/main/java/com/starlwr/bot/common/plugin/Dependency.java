package com.starlwr.bot.common.plugin;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Objects;

/**
 * 依赖信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Dependency {
    /**
     * 组名
     */
    private String groupId;

    /**
     * 依赖名
     */
    private String artifactId;

    /**
     * 版本号
     */
    private String version;

    public Dependency(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Dependency that)) return false;
        return Objects.equals(groupId, that.groupId) && Objects.equals(artifactId, that.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId);
    }
}
