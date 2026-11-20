package com.starlwr.bot.core.config;

import ch.qos.logback.classic.Level;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import com.alibaba.fastjson2.writer.ObjectWriterProvider;
import com.starlwr.bot.core.painter.converter.ColorConverter;
import com.starlwr.bot.core.painter.converter.FontConverter;
import org.springframework.boot.convert.DurationStyle;

import java.awt.Color;
import java.awt.Font;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 配置值转换工具类
 */
public class ConfigValueConverter {
    /**
     * 配置值转换所用的独立 JSON 读取器
     */
    private static final ObjectReaderProvider READERS = new ObjectReaderProvider();

    /**
     * 配置值转换所用的独立 JSON 写入器
     */
    private static final ObjectWriterProvider WRITERS = new ObjectWriterProvider();

    static {
        READERS.register(Level.class, (reader, type, name, features) -> {
            String value = reader.readString();
            if (value == null) {
                return null;
            }
            Level level = Level.toLevel(value.toUpperCase(Locale.ROOT), null);
            if (level == null) {
                throw new IllegalArgumentException("无效的日志级别");
            }
            return level;
        });
        READERS.register(Duration.class, (reader, type, name, features) -> {
            String value = reader.readString();
            return value == null ? null : DurationStyle.detectAndParse(value);
        });
        READERS.register(Color.class, (reader, type, name, features) -> {
            Object value = reader.readAny();
            return value instanceof Number number ? new Color(number.intValue(), true)
                    : value == null ? null : new ColorConverter().convert(value.toString());
        });
        READERS.register(Font.class, (reader, type, name, features) -> {
            Object value = reader.readAny();
            if (value instanceof Map<?, ?> map) {
                return new Font(map.get("name").toString(), ((Number) map.get("style")).intValue(), ((Number) map.get("size")).intValue());
            }
            return value == null ? null : new FontConverter().convert(value.toString());
        });
        for (Class<?> type : List.of(Level.class, Duration.class, Color.class, Font.class)) {
            READERS.register(type, READERS.getObjectReader(type), true);
        }
        WRITERS.register(Level.class, (writer, value, name, type, features) -> writer.writeString(value.toString()));
        WRITERS.register(Duration.class, (writer, value, name, type, features) -> writer.writeString(value.toString()));
        WRITERS.register(Color.class, (writer, value, name, type, features) -> writer.writeInt32(((Color) value).getRGB()));
        WRITERS.register(Font.class, (writer, value, name, type, features) -> {
            Font font = (Font) value;
            writer.writeAny(Map.of("name", font.getName(), "style", font.getStyle(), "size", font.getSize()));
        });
    }

    private ConfigValueConverter() {
    }

    /**
     * 把配置值转成指定类型的副本，保留集合元素的类型
     * @param value 原配置值
     * @param type 目标类型
     * @param <T> 目标值类型
     * @return 转换后的独立副本
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object value, Type type) {
        // 处理空值和基本类型的转换限制
        Objects.requireNonNull(type, "配置类型不能为空");
        if (value == null) {
            if (type instanceof Class<?> target && target.isPrimitive()) {
                throw new IllegalArgumentException("配置值不能转换为基本类型 " + target.getName());
            }
            return null;
        }

        // 将通用对象或集合复制为独立的 JSON 数据
        if (type == Object.class || type == Map.class || type == List.class || type == Collection.class) {
            Object result = toTree(value);
            if (type != Object.class && !((Class<?>) type).isInstance(result)) {
                throw new IllegalArgumentException("配置类型不匹配: " + type.getTypeName());
            }
            return (T) result;
        }

        // 将 JSON 转成配置类、枚举或其他指定类型
        try {
            JSONReader.Context context = new JSONReader.Context(READERS, JSONReader.Feature.SupportSmartMatch,
                    JSONReader.Feature.FieldBased, JSONReader.Feature.UseDefaultConstructorAsPossible,
                    JSONReader.Feature.ErrorOnEnumNotMatch, JSONReader.Feature.ErrorOnNullForPrimitives);
            return JSON.parseObject(toJson(value), type, context);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("配置值不能转换为 " + type.getTypeName());
        }
    }

    /**
     * 将 Java 属性名转成小写短横线格式的配置键
     * @param name Java 属性名
     * @return 配置键
     */
    public static String toConfigKey(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /**
     * 将配置值复制成独立的 JSON 对象或值
     * @param value 原配置值
     * @return 独立数据副本
     */
    public static Object toTree(Object value) {
        return JSON.parse(toJson(value));
    }

    /**
     * 根据配置字段的命名和类型规则生成 JSON
     * @param value 原配置值
     * @return 配置 JSON
     */
    private static String toJson(Object value) {
        JSONWriter.Context context = new JSONWriter.Context(WRITERS, JSONWriter.Feature.WriteNulls);
        context.setNameFilter((object, name, item) -> object instanceof Map<?, ?> ? name : toConfigKey(name));
        return JSON.toJSONString(value, context);
    }
}
