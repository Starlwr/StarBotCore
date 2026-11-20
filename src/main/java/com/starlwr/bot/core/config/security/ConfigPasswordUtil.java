package com.starlwr.bot.core.config.security;

import com.starlwr.bot.core.config.validation.ConfigRules;
import com.nulabinc.zxcvbn.Zxcvbn;
import org.apache.commons.lang3.StringUtils;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 配置中心密码工具类
 */
public final class ConfigPasswordUtil {
    /**
     * 配置中心访问密码路径
     */
    public static final List<String> PASSWORD_PATH = List.of("starbot", "core", "config-center", "password");

    /**
     * 访问密码的长度上限
     */
    public static final int MAX_LENGTH = 32;

    /**
     * 访问密码的长度下限
     */
    private static final int MIN_LENGTH = 8;

    /**
     * 密码派生算法
     */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /**
     * PBKDF2 迭代次数
     */
    private static final int ITERATIONS = 600000;

    /**
     * 随机盐、密码摘要和强度评分的编码格式
     */
    private static final Pattern ENCODED_PATTERN = Pattern.compile("[A-Za-z0-9_-]{22}\\$[A-Za-z0-9_-]{43}\\$[0-4]");

    /**
     * 随机盐生成器
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private ConfigPasswordUtil() {
    }

    /**
     * 将明文密码转为哈希，已编码的密码直接返回
     * @param password 明文或已编码的密码
     * @return 密码哈希，未设置密码时返回空字符串
     */
    public static String encode(String password) {
        if (StringUtils.isEmpty(password)) {
            return "";
        }
        if (isEncoded(password)) {
            return password;
        }
        if (!ConfigRules.isValidFormat(password, "password")) {
            throw new SecurityException("访问密码只能使用英文字母、数字和半角符号");
        }
        if (password.length() < MIN_LENGTH) {
            throw new SecurityException("访问密码不能少于 " + MIN_LENGTH + " 个字符");
        }
        if (password.length() > MAX_LENGTH) {
            throw new SecurityException("访问密码不能超过 " + MAX_LENGTH + " 个字符");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        int strength = new Zxcvbn().measure(password).getScore();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(salt) + "$"
                + encoder.encodeToString(derive(password, salt, strength)) + "$" + strength;
    }

    /**
     * 检查输入的密码是否与保存的摘要匹配
     * @param password 输入的密码
     * @param encoded 已保存的哈希
     * @return 密码是否匹配
     */
    public static boolean matches(String password, String encoded) {
        int length = StringUtils.length(password);
        if (length < MIN_LENGTH || length > MAX_LENGTH || !ConfigRules.isValidFormat(password, "password")) {
            return false;
        }
        String[] parts = parse(encoded);
        byte[] salt = Base64.getUrlDecoder().decode(parts[0]);
        return MessageDigest.isEqual(Base64.getUrlDecoder().decode(parts[1]), derive(password, salt, Integer.parseInt(parts[2])));
    }

    /**
     * 根据密码哈希中的强度评分判断密码是否容易猜测
     * @param encoded 密码哈希
     * @return 是否容易猜测
     */
    public static boolean isWeak(String encoded) {
        return Integer.parseInt(parse(encoded)[2]) < 3;
    }

    /**
     * 检查配置值是否是有效的密码摘要格式
     * @param value 待检查值
     * @return 是否为合法编码
     */
    public static boolean isEncoded(Object value) {
        return value instanceof String text && ENCODED_PATTERN.matcher(text).matches();
    }

    /**
     * 根据密码、随机盐和强度评分计算摘要
     * @param password 明文密码
     * @param salt 随机盐
     * @param strength 密码强度
     * @return 派生摘要
     */
    private static byte[] derive(String password, byte[] salt, int strength) {
        byte[] combined = java.util.Arrays.copyOf(salt, salt.length + 1);
        combined[salt.length] = (byte) strength;
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), combined, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法处理配置中心访问密码", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 检查密码哈希的格式并拆分各部分
     * @param encoded 密码哈希
     * @return 随机盐、密码摘要和强度评分
     */
    private static String[] parse(String encoded) {
        if (!isEncoded(encoded)) {
            throw new SecurityException("配置中心访问密码哈希格式无效, 请重新设置密码");
        }
        return encoded.split("\\$", -1);
    }
}
