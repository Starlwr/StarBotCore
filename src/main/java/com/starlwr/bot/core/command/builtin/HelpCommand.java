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

/**
 * 帮助命令
 */
@Slf4j
@Component
public class HelpCommand implements StarBotCommand {
    private static final int WIDTH = 1000;

    private static final int MARGIN = 50;

    private static final int CONTENT_X = 106;

    private static final int CONTENT_WIDTH = 650;

    private static final int COMMAND_GAP = 38;

    private static final Color COLOR_DEEP_PURPLE = new Color(121, 86, 237);

    private static final Color COLOR_MIDDLE_PURPLE = new Color(176, 164, 227);

    private static final Color COLOR_LIGHT_PURPLE = new Color(233, 231, 239);

    private static final Color COLOR_DIVIDER = new Color(222, 225, 235);

    private static final Color COLOR_AVAILABLE = new Color(24, 167, 82);

    private static final Color COLOR_DISABLED = new Color(205, 76, 76);

    private static final Color COLOR_PERMISSION_DENIED = new Color(238, 124, 35);

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
        return List.of("菜单", "命令", "指令", "help");
    }

    @Override
    public String getDescription() {
        return "查看命令列表、使用说明及当前可用状态";
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
        CommonPainter painter = factory.create(WIDTH, 2000, true);
        drawLogo(painter);
        drawTitle(painter, "命令列表", "共 " + views.size() + " 个命令");

        for (int index = 0; index < views.size(); index++) {
            CommandView view = views.get(index);

            BufferedImage content = renderCommand(view.getCommand());
            int rowHeight = Math.max(content.getHeight(), 55) + COMMAND_GAP;
            int rowY = painter.getY();

            painter.drawTextWithStyle(List.of(new TextWithStyle("➤", CommonPainter.SECTION_FONT_SIZE, COLOR_DEEP_PURPLE)), new Point(MARGIN, rowY));
            painter.drawImage(content, new Point(CONTENT_X, rowY));

            String state = "● " + view.getState().getText();
            int stateWidth = painter.getStringWidthAndHeight(state, 26).getLeft();
            painter.drawTextWithStyle(List.of(new TextWithStyle(state, 26, view.getState().getColor(), Font.BOLD)), new Point(WIDTH - MARGIN - stateWidth, rowY + 12));

            painter.movePos(0, rowHeight);
            if (index < views.size() - 1) {
                painter.drawRectangle(MARGIN - 8, painter.getY() - COMMAND_GAP / 2, WIDTH - (MARGIN - 8) * 2, 2, COLOR_DIVIDER);
            }
        }

        painter.movePos(0, 35);
        painter.drawCopyright(MARGIN);
        painter.movePos(0, 20);

        return painter.createSolidRoundedRectangleBackground(Color.WHITE, 35).base64();
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
        if (properties.getPaint().isDrawLogo()) {
            try (InputStream input = resourceLoader.getResource("classpath:logo.png").getInputStream()) {
                BufferedImage logo = ImageIO.read(input);
                painter.drawImage(logo, new Point(200, 55)).setPos(MARGIN, 275);
            } catch (IOException e) {
                log.error("加载 StarBot Logo 失败", e);
            }
        } else {
            painter.setPos(MARGIN, 50);
        }
    }

    /**
     * 绘制模块标题及提示文字
     * @param painter 绘图器
     * @param title 模块标题
     * @param tip 提示文字
     */
    private void drawTitle(CommonPainter painter, String title, String tip) {
        int y = painter.getY();
        Pair<Integer, Integer> titleSize = painter.getStringWidthAndHeight(title, CommonPainter.CHAPTER_FONT_SIZE);
        painter.drawPolygon(List.of(
                new Point(MARGIN, y + 8),
                new Point(MARGIN + titleSize.getLeft() + 48, y + 8),
                new Point(MARGIN + titleSize.getLeft() + 64, y + titleSize.getRight() + 16),
                new Point(MARGIN, y + titleSize.getRight() + 16)
        ), COLOR_LIGHT_PURPLE);

        painter.drawPolygon(List.of(
                new Point(MARGIN, y),
                new Point(MARGIN + titleSize.getLeft() + 32, y),
                new Point(MARGIN + titleSize.getLeft() + 48, y + titleSize.getRight() + 16),
                new Point(MARGIN, y + titleSize.getRight() + 16)
        ), COLOR_MIDDLE_PURPLE);

        painter.drawRectangle(MARGIN, y + titleSize.getRight() + 12, WIDTH - MARGIN * 2, 4, COLOR_LIGHT_PURPLE);
        painter.drawChapter(title, new Point(MARGIN + 16, y + 8));

        int lineY = y + painter.getStringWidthAndHeight(title, CommonPainter.SECTION_FONT_SIZE).getRight() + 12;
        Pair<Integer, Integer> tipSize = painter.getStringWidthAndHeight(tip, CommonPainter.TEXT_FONT_SIZE);
        painter.drawText(tip, COLOR_DEEP_PURPLE, new Point(WIDTH - MARGIN * 2 - tipSize.getLeft(), lineY - tipSize.getRight() - 5));

        painter.setPos(MARGIN, y + titleSize.getRight() + 50);
    }

    /**
     * 绘制单个命令的帮助图片
     * @param command 命令
     * @return 单个命令的帮助图片
     */
    private BufferedImage renderCommand(StarBotCommand command) {
        String prefix = StringUtils.defaultString(properties.getCommand().getPrefix());

        CommonPainter painter = factory.create(CONTENT_WIDTH, 220, true).setPos(0, 0);
        painter.drawTextWithStyle(List.of(new TextWithStyle(prefix + command.getName(), 36, Color.BLACK, Font.BOLD)), null, true, 0);
        if (CollectionUtils.isNotEmpty(command.getAliases())) {
            String aliases = String.join("、", command.getAliases());
            painter.drawTextWithStyle(List.of(new TextWithStyle("别名: " + aliases, 23, Color.GRAY, Font.PLAIN)), null, true, 0);
        }
        painter.drawTextWithStyle(List.of(new TextWithStyle(command.getDescription(), 27, Color.BLACK, Font.PLAIN)), null, true, 0);

        CommonPainter usage = factory.create(CONTENT_WIDTH - 32, 60, true).setPos(0, 0);
        usage.drawTextWithStyle(List.of(new TextWithStyle("用法: " + prefix + command.getUsage(), 25, COLOR_DEEP_PURPLE, Font.BOLD)), null, true, 0);
        usage.setPos(null, Math.max(1, usage.getY() - usage.getRowSpace()));
        usage.expandHeightIfNeeded(Math.max(1, usage.getY()));
        BufferedImage usageImage = usage.createSolidRoundedRectangleBackground(new Color(0, 0, 0, 0), 0).getBufferedImage();

        int usageY = painter.getY() + 3;
        painter.drawRoundedRectangle(0, usageY, CONTENT_WIDTH, usageImage.getHeight() + 14, 12, COLOR_LIGHT_PURPLE);
        painter.drawImage(usageImage, new Point(16, usageY + 7));
        painter.setPos(0, usageY + usageImage.getHeight() + 18);
        painter.expandHeightIfNeeded(Math.max(1, painter.getY()));

        return painter.createSolidRoundedRectangleBackground(new Color(0, 0, 0, 0), 0).getBufferedImage();
    }

}
