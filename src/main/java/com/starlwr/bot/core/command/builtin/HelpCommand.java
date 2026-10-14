package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.command.StarBotCommandPolicy;
import com.starlwr.bot.core.command.StarBotCommandRegistry;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.painter.StarBotCommonPainterFactory;
import com.starlwr.bot.core.painter.TextWithStyle;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帮助命令
 */
@Slf4j
@Component
public class HelpCommand implements StarBotCommand {
    private static final int WIDTH = 1200;

    private static final int MARGIN = 50;

    private static final int PADDING = 32;

    private static final int COMMAND_GAP = 22;

    private static final int LINE_GAP = 6;

    private static final Color COLOR_DEEP_PURPLE = new Color(78, 45, 128);

    private static final Color COLOR_ACCENT = new Color(121, 86, 237);

    private static final Color COLOR_BACKGROUND = new Color(245, 241, 255);

    private static final Color COLOR_BORDER = new Color(207, 188, 250);

    private static final Color COLOR_DIVIDER = new Color(237, 230, 247);

    private static final Color COLOR_TEXT = new Color(83, 69, 99);

    private static final Color COLOR_MUTED = new Color(140, 129, 157);

    private static final Color COLOR_ID = new Color(149, 134, 170);

    private static final Color COLOR_STAR = new Color(255, 229, 154);

    private static final Color COLOR_PINK = new Color(252, 203, 224);

    private static final Color COLOR_AVAILABLE = new Color(36, 150, 94);

    private static final Color COLOR_DISABLED = new Color(199, 92, 105);

    private static final Color COLOR_PERMISSION_DENIED = new Color(190, 129, 45);

    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private static final Pattern TEXT_PARTS = Pattern.compile("\\r\\n|[\\r\\n]|<[^<>\\r\\n]*>|\\[[^\\[\\]\\r\\n]*]|[^\\S\\r\\n]+|[^\\s<\\[]+|.", Pattern.UNICODE_CHARACTER_CLASS);

    private final StarBotCommandRegistry registry;

    private final StarBotCommandPolicy policy;

    private final StarBotCoreProperties properties;

    private final StarBotCommonPainterFactory factory;

    private final ResourceLoader resourceLoader;

    /**
     * 命令状态视图
     */
    @Getter
    @AllArgsConstructor
    private static final class CommandView {
        /**
         * 命令
         */
        private final StarBotCommand command;

        /**
         * 帮助菜单的命令状态
         */
        private final CommandState state;
    }

    /**
     * 含格式文本的换行结果
     */
    @Getter
    @AllArgsConstructor
    private static final class TextLayout {
        /**
         * 每行的含格式文本
         */
        private final List<List<TextWithStyle>> lines;

        /**
         * 单行文字高度
         */
        private final int lineHeight;

        /**
         * 获取包含行间距的实际高度
         * @return 文本高度
         */
        private int getHeight() {
            return lines.isEmpty() ? 0 : lines.size() * lineHeight + (lines.size() - 1) * LINE_GAP;
        }
    }

    /**
     * 帮助菜单的命令状态
     */
    @Getter
    @AllArgsConstructor
    private enum CommandState {
        AVAILABLE("可用", COLOR_AVAILABLE),
        DISABLED("禁用", COLOR_DISABLED),
        PERMISSION_DENIED("权限不足", COLOR_PERMISSION_DENIED),
        PRIVATE_ONLY("仅私聊可用", COLOR_PERMISSION_DENIED),
        GROUP_ONLY("仅群聊可用", COLOR_PERMISSION_DENIED);

        private final String text;

        private final Color color;
    }

    @Autowired
    public HelpCommand(StarBotCommandRegistry registry, StarBotCommandPolicy policy, StarBotCoreProperties properties, StarBotCommonPainterFactory factory) {
        this.registry = registry;
        this.policy = policy;
        this.properties = properties;
        this.factory = factory;
        this.resourceLoader = new DefaultResourceLoader(getClass().getClassLoader());
    }

    @Override
    public String getId() {
        return "starbot.builtin.help";
    }

    @Override
    public String getName() {
        return "帮助";
    }

    @Override
    public List<String> getAliases() {
        return List.of("菜单", "命令", "指令", "help", "menu");
    }

    @Override
    public String getDescription() {
        return "查看命令列表、使用说明及当前可用状态";
    }

    @Override
    public int getArgumentCount() {
        return 0;
    }

    @Override
    public void execute(CommandContext context) {
        Optional<String> optionalBase64 = render(context);
        if (optionalBase64.isPresent()) {
            context.reply("{image_base64=" + optionalBase64.get() + "}");
        } else {
            log.error("生成帮助图片失败");
        }
    }

    /**
     * 绘制帮助图片
     * @param context 命令上下文
     * @return 帮助图片的 Base64 字符串
     */
    private Optional<String> render(CommandContext context) {
        List<CommandView> views = commandViews(context);
        CommonPainter painter = factory.create(WIDTH, 2000, true).setPos(MARGIN, 50);
        drawLogo(painter);
        drawHeaderDecoration(painter);
        String title = properties.getPaint().isDrawLogo() ? "命令列表" : "StarBot 命令列表";
        drawTitle(painter, title, "共 " + views.size() + " 个命令");

        for (CommandView view : views) {
            BufferedImage card = renderCommand(view.getCommand(), view.getState());
            int y = painter.getY();
            painter.drawImage(card, new Point(MARGIN, y));
            painter.setPos(MARGIN, y + card.getHeight() + COMMAND_GAP);
        }

        drawFooter(painter);
        return painter.createSolidRoundedRectangleBackground(COLOR_BACKGROUND, 35).base64();
    }

    /**
     * 获取不包含全局禁用的命令状态视图列表
     * @param context 命令上下文
     * @return 不包含全局禁用的命令状态视图列表
     */
    private List<CommandView> commandViews(CommandContext context) {
        List<CommandView> views = new ArrayList<>();
        for (StarBotCommand command : registry.getCommands()) {
            StarBotCommandPolicy.Decision decision = policy.evaluate(command, context);
            if (!decision.isGlobalDisabled()) {
                views.add(new CommandView(command, state(command, decision)));
            }
        }
        return List.copyOf(views);
    }

    /**
     * 获取帮助菜单的命令状态
     * @param command 命令
     * @param decision 命令策略检查结果
     * @return 帮助菜单的命令状态
     */
    private CommandState state(StarBotCommand command, StarBotCommandPolicy.Decision decision) {
        if (decision.isGroupDisabled()) {
            return CommandState.DISABLED;
        }
        if (!decision.isScopeAllowed()) {
            return switch (command.getScope()) {
                case PRIVATE -> CommandState.PRIVATE_ONLY;
                case GROUP -> CommandState.GROUP_ONLY;
                case ALL -> CommandState.DISABLED;
            };
        }
        if (!decision.isPermissionAllowed()) {
            return CommandState.PERMISSION_DENIED;
        }
        return CommandState.AVAILABLE;
    }

    /**
     * 绘制 StarBot Logo
     * @param painter 绘图器
     */
    private void drawLogo(CommonPainter painter) {
        if (!properties.getPaint().isDrawLogo()) {
            return;
        }

        try (InputStream input = resourceLoader.getResource("classpath:logo.png").getInputStream()) {
            BufferedImage logo = ImageIO.read(input);
            if (logo != null) {
                painter.drawImage(logo, new Point((WIDTH - logo.getWidth()) / 2, 55)).setPos(MARGIN, 275);
            }
        } catch (IOException e) {
            log.error("加载 StarBot Logo 失败", e);
        }
    }

    /**
     * 绘制顶部装饰
     * @param painter 绘图器
     */
    private void drawHeaderDecoration(CommonPainter painter) {
        if (!properties.getPaint().isDrawLogo()) {
            return;
        }

        painter.drawStar(100, 100, 26, COLOR_STAR);
        painter.drawStar(WIDTH - 105, 175, 22, COLOR_STAR);
        painter.drawStar(90, 205, 15, Color.WHITE);
        painter.drawStar(WIDTH - 80, 85, 14, Color.WHITE);
        painter.drawRoundedRectangle(130, 160, 20, 20, 20, COLOR_BORDER);
        painter.drawRoundedRectangle(WIDTH - 125, 85, 20, 20, 20, COLOR_PINK);
    }

    /**
     * 绘制模块标题及提示文字
     * @param painter 绘图器
     * @param title 模块标题
     * @param tip 提示文字
     */
    private void drawTitle(CommonPainter painter, String title, String tip) {
        int y = painter.getY() + 15;
        TextWithStyle heading = new TextWithStyle(title, 38, COLOR_DEEP_PURPLE, Font.BOLD);
        Pair<Integer, Integer> titleSize = painter.getStringWidthAndHeight(heading);
        int lineWidth = (WIDTH - titleSize.getLeft()) / 2 - MARGIN - 40;
        int lineY = y + titleSize.getRight() / 2;
        painter.drawRectangle(MARGIN, lineY, lineWidth, 2, COLOR_BORDER);
        painter.drawRectangle(WIDTH - MARGIN - lineWidth, lineY, lineWidth, 2, COLOR_BORDER);
        painter.drawTextWithStyle(List.of(heading), new Point((WIDTH - titleSize.getLeft()) / 2, y));

        TextWithStyle subtitle = new TextWithStyle(tip, 26, COLOR_MUTED);
        Pair<Integer, Integer> tipSize = painter.getStringWidthAndHeight(subtitle);
        int tipY = y + titleSize.getRight() + 10;
        painter.drawTextWithStyle(List.of(subtitle), new Point((WIDTH - tipSize.getLeft()) / 2, tipY));
        painter.setPos(MARGIN, tipY + tipSize.getRight() + 26);
    }

    /**
     * 绘制单个命令的帮助图片
     * @param command 命令
     * @param state 命令状态
     * @return 单个命令卡片
     */
    private BufferedImage renderCommand(StarBotCommand command, CommandState state) {
        String prefix = StringUtils.defaultString(properties.getCommand().getPrefix());
        int width = WIDTH - MARGIN * 2;
        int contentWidth = width - PADDING * 2;
        CommonPainter painter = factory.create(width, 300, true);

        BufferedImage badge = renderState(painter, state);
        int titleWidth = contentWidth - badge.getWidth() - 46;
        BufferedImage title = renderLayout(layoutText(painter, List.of(
                new TextWithStyle(prefix + command.getName(), 36, COLOR_DEEP_PURPLE, Font.BOLD),
                new TextWithStyle("   " + command.getId(), 24, COLOR_ID)
        ), titleWidth), titleWidth);
        BufferedImage description = renderLayout(layoutText(painter, List.of(new TextWithStyle(command.getDescription(), 28, COLOR_TEXT)), contentWidth), contentWidth);
        BufferedImage label = renderLayout(layoutText(painter, List.of(new TextWithStyle("别名", 20, COLOR_MUTED)), contentWidth), contentWidth);

        int columnWidth = (contentWidth - 48) / 2;
        int usageX = PADDING + columnWidth + 48;
        String usageText = prefix + StringUtils.defaultString(command.getUsage());
        String name = prefix + command.getName();
        List<TextWithStyle> usageTexts;
        if (usageText.startsWith(name) && (usageText.length() == name.length() || Character.isWhitespace(usageText.codePointAt(name.length())))) {
            usageTexts = List.of(new TextWithStyle(name, 26, COLOR_ACCENT, Font.BOLD), new TextWithStyle(usageText.substring(name.length()), 26, COLOR_ID));
        } else {
            usageTexts = List.of(new TextWithStyle(usageText, 26, COLOR_ACCENT));
        }

        TextLayout usageLayout = layoutText(painter, usageTexts, columnWidth);
        boolean fullWidth = usageLayout.getLines().size() > 2;
        if (fullWidth) {
            usageLayout = layoutText(painter, usageTexts, contentWidth);
        }
        BufferedImage usage = renderLayout(usageLayout, fullWidth ? contentWidth : columnWidth);
        BufferedImage aliases = renderAliases(command.getAliases(), fullWidth ? contentWidth - 64 : columnWidth);

        int descriptionY = 24 + Math.max(title.getHeight(), badge.getHeight()) + 8;
        int dividerY = descriptionY + description.getHeight() + 16;
        int lowerY = dividerY + 16;
        int usageLabelY = lowerY;
        int usageY;
        int height;
        if (fullWidth) {
            int aliasHeight = Math.max(label.getHeight(), aliases.getHeight());
            usageLabelY = lowerY + aliasHeight + 32;
            usageY = usageLabelY + label.getHeight() + 8;
            height = usageY + usage.getHeight() + 18;
        } else {
            usageY = lowerY + label.getHeight() + 8;
            height = usageY + Math.max(aliases.getHeight(), usage.getHeight()) + 18;
        }

        painter.drawRoundedRectangle(0, 0, width, height, 32, COLOR_BORDER);
        painter.drawRoundedRectangle(2, 2, width - 4, height - 4, 28, Color.WHITE);
        painter.drawRoundedRectangle(PADDING, 28, 5, 32, 5, COLOR_BORDER);
        painter.drawImage(title, new Point(PADDING + 22, 24));
        painter.drawImage(badge, new Point(width - PADDING - badge.getWidth(), 24));
        painter.drawImage(description, new Point(PADDING, descriptionY));
        painter.drawRectangle(PADDING, dividerY, contentWidth, 1, COLOR_DIVIDER);
        painter.drawImage(label, new Point(PADDING, lowerY));

        if (fullWidth) {
            painter.drawImage(aliases, new Point(PADDING + 64, lowerY));
            painter.drawRectangle(PADDING, usageLabelY - 16, contentWidth, 1, COLOR_DIVIDER);
            usageX = PADDING;
        } else {
            painter.drawImage(aliases, new Point(PADDING, usageY));
            painter.drawRectangle(PADDING + columnWidth + 24, lowerY, 1, height - lowerY - 18, COLOR_DIVIDER);
        }

        painter.drawPolygon(List.of(new Point(usageX, usageLabelY + 5), new Point(usageX + 8, usageLabelY + 12), new Point(usageX, usageLabelY + 19),
                new Point(usageX + 2, usageLabelY + 21), new Point(usageX + 11, usageLabelY + 12), new Point(usageX + 2, usageLabelY + 3)), COLOR_ACCENT);
        painter.drawRectangle(usageX + 15, usageLabelY + 19, 9, 2, COLOR_ACCENT);
        painter.drawTextWithStyle(List.of(new TextWithStyle("用法", 20, COLOR_MUTED)), new Point(usageX + 33, usageLabelY));
        painter.drawImage(usage, new Point(usageX, usageY));

        return painter.setPos(0, Math.max(1, height)).createSolidRoundedRectangleBackground(TRANSPARENT, 0).getBufferedImage();
    }

    /**
     * 绘制命令状态
     * @param painter 绘图器
     * @param state 命令状态
     * @return 命令状态图片
     */
    private BufferedImage renderState(CommonPainter painter, CommandState state) {
        TextWithStyle text = new TextWithStyle(state.getText(), 24, state.getColor(), Font.BOLD);
        Pair<Integer, Integer> size = painter.getStringWidthAndHeight(text);
        int width = size.getLeft() + 46;
        int height = Math.max(44, size.getRight() + 14);

        Color color = state.getColor();
        Color borderColor = new Color((color.getRed() * 22 + 255 * 78) / 100, (color.getGreen() * 22 + 255 * 78) / 100, (color.getBlue() * 22 + 255 * 78) / 100);
        Color backgroundColor = new Color((color.getRed() * 9 + 255 * 91) / 100, (color.getGreen() * 9 + 255 * 91) / 100, (color.getBlue() * 9 + 255 * 91) / 100);

        CommonPainter badge = factory.create(width, height + 20, true);
        badge.drawRoundedRectangle(0, 0, width, height, height, borderColor);
        badge.drawRoundedRectangle(1, 1, width - 2, height - 2, height - 2, backgroundColor);
        badge.drawRoundedRectangle(15, height / 2 - 4, 8, 8, 8, state.getColor());
        badge.drawTextWithStyle(List.of(text), new Point(30, (height - size.getRight()) / 2));

        return badge.setPos(0, height).createSolidRoundedRectangleBackground(TRANSPARENT, 0).getBufferedImage();
    }

    /**
     * 获取含格式文本的换行结果
     * @param painter 绘图器
     * @param texts 含格式文本
     * @param width 可用宽度
     * @return 含格式文本的换行结果
     */
    private TextLayout layoutText(CommonPainter painter, List<TextWithStyle> texts, int width) {
        List<List<TextWithStyle>> lines = new ArrayList<>();
        List<TextWithStyle> line = new ArrayList<>();
        int lineWidth = 0;
        int lineHeight = 1;
        for (TextWithStyle text : texts) {
            lineHeight = Math.max(lineHeight, painter.getStringWidthAndHeight(text).getRight());
            Font font = text.getFont();
            int size = text.getSize();
            Color color = text.getColor();
            int style = text.getStyle();

            Matcher matcher = TEXT_PARTS.matcher(StringUtils.defaultString(text.getText()));
            while (matcher.find()) {
                String part = matcher.group();

                if ("\n".equals(part) || "\r".equals(part) || "\r\n".equals(part)) {
                    lines.add(List.copyOf(line));
                    line = new ArrayList<>();
                    lineWidth = 0;
                    continue;
                }

                TextWithStyle partText = new TextWithStyle(part, font, size, color, style);
                int partWidth = painter.getStringWidthAndHeight(partText).getLeft();
                if (partWidth <= width) {
                    if (lineWidth + partWidth > width && !line.isEmpty()) {
                        lines.add(List.copyOf(line));
                        line = new ArrayList<>();
                        lineWidth = 0;
                    }
                    line.add(partText);
                    lineWidth += partWidth;
                    continue;
                }

                StringBuilder fragment = new StringBuilder();
                for (int offset = 0; offset < part.length(); ) {
                    int codePoint = part.codePointAt(offset);
                    String character = new String(Character.toChars(codePoint));
                    int characterWidth = painter.getStringWidthAndHeight(new TextWithStyle(character, font, size, color, style)).getLeft();

                    if (lineWidth + characterWidth > width && (lineWidth > 0 || !fragment.isEmpty())) {
                        if (!fragment.isEmpty()) {
                            line.add(new TextWithStyle(fragment.toString(), font, size, color, style));
                            fragment.setLength(0);
                        }
                        lines.add(List.copyOf(line));
                        line = new ArrayList<>();
                        lineWidth = 0;
                    }

                    fragment.appendCodePoint(codePoint);
                    lineWidth += characterWidth;
                    offset += Character.charCount(codePoint);
                }

                if (!fragment.isEmpty()) {
                    line.add(new TextWithStyle(fragment.toString(), font, size, color, style));
                }
            }
        }

        if (CollectionUtils.isNotEmpty(line) || CollectionUtils.isNotEmpty(lines)) {
            lines.add(List.copyOf(line));
        }

        return new TextLayout(List.copyOf(lines), lineHeight);
    }

    /**
     * 根据含格式文本的换行结果绘制文本块
     * @param layout 含格式文本的换行结果
     * @param width 文本块宽度
     * @return 文本块图片
     */
    private BufferedImage renderLayout(TextLayout layout, int width) {
        CommonPainter painter = factory.create(width, Math.max(1, layout.getHeight()) + 20, true);
        int y = 0;

        for (List<TextWithStyle> line : layout.getLines()) {
            int x = 0;
            for (TextWithStyle text : line) {
                Pair<Integer, Integer> size = painter.getStringWidthAndHeight(text);
                painter.drawTextWithStyle(List.of(text), new Point(x, y + layout.getLineHeight() - size.getRight()));
                x += size.getLeft();
            }
            y += layout.getLineHeight() + LINE_GAP;
        }

        return painter.setPos(0, Math.max(1, layout.getHeight())).createSolidRoundedRectangleBackground(TRANSPARENT, 0).getBufferedImage();
    }

    /**
     * 绘制别名标签
     * @param aliases 命令别名列表
     * @param width 可用宽度
     * @return 别名标签图片
     */
    private BufferedImage renderAliases(List<String> aliases, int width) {
        CommonPainter painter = factory.create(width, 80, true);
        if (CollectionUtils.isEmpty(aliases)) {
            painter.drawTextWithStyle(List.of(new TextWithStyle("无", 22, COLOR_MUTED)));
            return painter.setPos(0, Math.max(1, painter.getY() - painter.getRowSpace())).createSolidRoundedRectangleBackground(TRANSPARENT, 0).getBufferedImage();
        }

        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (String alias : aliases) {
            TextWithStyle text = new TextWithStyle(alias, 22, COLOR_ID);
            int tagWidth = Math.min(width, Math.max(25, painter.getStringWidthAndHeight(text).getLeft() + 24));
            BufferedImage content = renderLayout(layoutText(painter, List.of(text), tagWidth - 24), tagWidth - 24);
            int tagHeight = content.getHeight() + 12;

            if (x > 0 && x + tagWidth > width) {
                x = 0;
                y += rowHeight + 8;
                rowHeight = 0;
            }

            painter.drawRoundedRectangle(x, y, tagWidth, tagHeight, 18, new Color(223, 212, 243));
            painter.drawRoundedRectangle(x + 1, y + 1, tagWidth - 2, tagHeight - 2, 16, Color.WHITE);
            painter.drawImage(content, new Point(x + 12, y + 6));
            x += tagWidth + 9;
            rowHeight = Math.max(rowHeight, tagHeight);
        }

        return painter.setPos(0, Math.max(1, y + rowHeight)).createSolidRoundedRectangleBackground(TRANSPARENT, 0).getBufferedImage();
    }

    /**
     * 绘制底部装饰粉白云朵、星星和圆点及版权信息
     * @param painter 绘图器
     */
    private void drawFooter(CommonPainter painter) {
        CommonPainter copyright = factory.create(WIDTH, 160, true).setPos(0, 18);
        copyright.drawCopyright(MARGIN);
        int height = Math.max(190, copyright.getY() + 65);
        BufferedImage copyrightImage = copyright.setPos(0, Math.max(1, copyright.getY())).createSolidRoundedRectangleBackground(TRANSPARENT, 0).getBufferedImage();

        CommonPainter footer = factory.create(WIDTH, height, false);
        Color cloud = new Color(255, 247, 250);
        Color pinkCloud = new Color(255, 222, 237);
        footer.drawRoundedRectangle(-70, height - 95, 190, 190, 190, cloud);
        footer.drawRoundedRectangle(55, height - 52, 145, 145, 145, cloud);
        footer.drawRoundedRectangle(WIDTH - 70, height - 105, 190, 190, 190, cloud);
        footer.drawRoundedRectangle(WIDTH - 150, height - 58, 135, 135, 135, cloud);
        footer.drawRoundedRectangle(WIDTH - 210, height - 30, 135, 135, 135, pinkCloud);
        footer.drawRoundedRectangle(WIDTH - 125, height - 17, 110, 110, 110, pinkCloud);

        footer.drawRoundedRectangle(128, 35, 23, 23, 23, COLOR_PINK);
        footer.drawRoundedRectangle(WIDTH - 265, height - 37, 19, 19, 19, COLOR_BORDER);
        footer.drawStar(210, 76, 19, Color.WHITE);
        footer.drawStar(75, height - 112, 12, COLOR_STAR);
        footer.drawStar(WIDTH - 110, height - 62, 11, COLOR_STAR);
        footer.drawStar(WIDTH - 190, height - 45, 19, Color.WHITE);
        footer.drawImage(copyrightImage, new Point(0, 0));

        int y = painter.getY() + 12;
        BufferedImage footerImage = footer.setPos(0, height).createSolidRoundedRectangleBackground(TRANSPARENT, 0).getBufferedImage();
        painter.drawImage(footerImage, new Point(0, y));
        painter.setPos(MARGIN, y + height);
    }

}
