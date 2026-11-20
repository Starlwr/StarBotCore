package com.starlwr.bot.core.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * 文件变更监听器
 */
@Slf4j
public class FileChangeWatcher implements AutoCloseable {
    private static final long DEBOUNCE_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final String name;

    private final Path path;

    private final WatchService watcher;

    private Thread thread;

    private boolean closed;

    public FileChangeWatcher(String name, Path path) throws IOException {
        this.name = name;
        this.path = path.toAbsolutePath().normalize();
        watcher = this.path.getFileSystem().newWatchService();
        try {
            this.path.getParent().register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException | RuntimeException e) {
            try {
                watcher.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /**
     * 关闭文件监听
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (thread != null) {
            thread.interrupt();
        }
        try {
            watcher.close();
        } catch (IOException e) {
            log.warn("{} 文件监听关闭失败: {}", name, path, e);
        }
    }

    /**
     * 启动文件监听
     * @param onChange 文件变化后执行的回调
     */
    public synchronized void start(Runnable onChange) {
        if (closed || thread != null) {
            return;
        }
        thread = new Thread(() -> watch(onChange), name + "-file-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 合并连续的文件变更通知，等待后执行回调
     * @param onChange 文件变化后执行的回调
     */
    private void watch(Runnable onChange) {
        try (watcher) {
            while (!Thread.currentThread().isInterrupted()) {
                if (!hasFileChange(watcher.take())) {
                    continue;
                }

                // 目标文件继续变化时，重新计算等待时间
                long deadline = System.nanoTime() + DEBOUNCE_NANOS;
                long remaining;
                while ((remaining = deadline - System.nanoTime()) > 0) {
                    WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                    if (key == null) {
                        break;
                    }
                    if (hasFileChange(key)) {
                        deadline = System.nanoTime() + DEBOUNCE_NANOS;
                    }
                }

                if (!Thread.currentThread().isInterrupted()) {
                    onChange.run();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
        } catch (IOException | RuntimeException e) {
            log.warn("{} 文件监听已停止: {}", name, path, e);
        }
    }

    /**
     * 检查当前通知是否需要重新读取目标文件
     * @param key 目录监听键
     * @return 是否需要重新读取目标文件
     */
    private boolean hasFileChange(WatchKey key) throws IOException {
        boolean changed = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            changed |= event.kind() == OVERFLOW || path.getFileName().equals(event.context());
        }
        if (!key.reset()) {
            throw new IOException("文件所在目录的监听已失效");
        }
        return changed;
    }
}
