package com.starlwr.bot.core.config.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置中心密码工具类测试
 */
class ConfigPasswordUtilTest {
    /**
     * 同一密码会生成不同摘要，登录仍需使用原密码
     */
    @Test
    void hashesWithFreshSaltAndVerifiesOnlyOriginalPassword() {
        String password = "A7!TestPassword#29";
        String first = ConfigPasswordUtil.encode(password), second = ConfigPasswordUtil.encode(password);
        assertThat(first).matches("[A-Za-z0-9_-]{22}\\$[A-Za-z0-9_-]{43}\\$[0-4]").isNotEqualTo(second).doesNotContain(password);
        assertThat(ConfigPasswordUtil.encode(first)).isEqualTo(first);
        assertThat(ConfigPasswordUtil.matches(password, first)).isTrue();
        assertThat(ConfigPasswordUtil.matches("wrong-password", first)).isFalse();
        assertThat(ConfigPasswordUtil.matches(first, first)).isFalse();
    }

    /**
     * 弱密码强度记录可在启动时读取，空密码不生成固定哈希
     */
    @Test
    void retainsStrengthAndLeavesBlankPasswordsUnset() {
        assertThat(ConfigPasswordUtil.isWeak(ConfigPasswordUtil.encode("12345678"))).isTrue();
        assertThat(ConfigPasswordUtil.encode("")).isEmpty();
        assertThat(ConfigPasswordUtil.encode(null)).isEmpty();
    }

    /**
     * 拒绝明文、损坏编码和超长密码
     */
    @Test
    void rejectsInvalidStoredHashesAndExcessiveInput() {
        assertThat(ConfigPasswordUtil.isEncoded("plaintext")).isFalse();
        assertThat(ConfigPasswordUtil.isEncoded("invalid$invalid$4")).isFalse();
        assertThatThrownBy(() -> ConfigPasswordUtil.encode("a".repeat(33))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> ConfigPasswordUtil.encode("A7!测试Password#29")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> ConfigPasswordUtil.encode(" ")).isInstanceOf(SecurityException.class);
        assertThat(ConfigPasswordUtil.matches("A7!测试Password#29", ConfigPasswordUtil.encode("A7!TestPassword#29"))).isFalse();
    }

    /**
     * 单独修改摘要中的强度评分会导致原密码校验失败
     */
    @Test
    void bindsStrengthScoreToPasswordDigest() {
        String encoded = ConfigPasswordUtil.encode("12345678");
        String modified = encoded.substring(0, encoded.lastIndexOf('$') + 1) + "4";
        assertThat(ConfigPasswordUtil.isEncoded(modified)).isTrue();
        assertThat(ConfigPasswordUtil.isWeak(modified)).isFalse();
        assertThat(ConfigPasswordUtil.matches("12345678", modified)).isFalse();
        assertThat(ConfigPasswordUtil.matches("12345678", encoded)).isTrue();
    }
}
