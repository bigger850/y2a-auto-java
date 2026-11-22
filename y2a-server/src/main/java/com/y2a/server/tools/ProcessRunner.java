package com.y2a.server.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 外部进程执行器（方案 A 的基础设施）。
 *
 * <p>统一处理 yt-dlp / ffmpeg 这类外部工具的调用：流式读取 stdout/stderr、
 * 逐行回调、超时强杀、协作式取消。所有外部工具调用都必须经由此类，
 * 避免各处重复写 ProcessBuilder 与流处理（模板方法 + 资源管理）。</p>
 */
public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    private ProcessRunner() {
    }

    /** 执行结果。 */
    public record Result(int exitCode, List<String> stdout, List<String> stderr, boolean timedOut, boolean cancelled) {
        public boolean ok() {
            return exitCode == 0 && !timedOut && !cancelled;
        }

        public String stderrText() {
            return String.join("\n", stderr);
        }

        public String lastError(int lines) {
            int from = Math.max(0, stderr.size() - lines);
            return String.join("\n", stderr.subList(from, stderr.size()));
        }
    }

    /** 执行选项（Builder 风格，便于逐步扩展）。 */
    public static final class Options {
        private Path workDir;
        private Duration timeout = Duration.ofMinutes(30);
        private Consumer<String> onStdout;
        private Consumer<String> onStderr;
        private AtomicBoolean cancelFlag;

        public Options workDir(Path dir) { this.workDir = dir; return this; }
        public Options timeout(Duration d) { this.timeout = d; return this; }
        public Options onStdout(Consumer<String> c) { this.onStdout = c; return this; }
        public Options onStderr(Consumer<String> c) { this.onStderr = c; return this; }
        public Options cancelFlag(AtomicBoolean flag) { this.cancelFlag = flag; return this; }
    }

    /**
     * 执行命令并等待完成。
     *
     * @param command 完整命令行（第一个元素为可执行文件）
     * @param options 执行选项
     */
    public static Result run(List<String> command, Options options) {
        Options opts = options == null ? new Options() : options;
        ProcessBuilder pb = new ProcessBuilder(command);
        if (opts.workDir != null) {
            pb.directory(opts.workDir.toFile());
        }
        pb.redirectErrorStream(false);
        // 关闭 stdin，避免外部工具等待交互
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null")));

        log.debug("执行外部命令: {}", String.join(" ", command));

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, List.of(), List.of("无法启动进程: " + e.getMessage()), false, false);
        }

        List<String> stdout = new CopyOnWriteArrayList<>();
        List<String> stderr = new CopyOnWriteArrayList<>();
        ExecutorService ioPool = Executors.newFixedThreadPool(2);
        Future<?> outTask = ioPool.submit(() -> pump(process.getInputStream(), stdout, opts.onStdout));
        Future<?> errTask = ioPool.submit(() -> pump(process.getErrorStream(), stderr, opts.onStderr));

        boolean timedOut = false;
        boolean cancelled = false;
        try {
            long deadline = System.nanoTime() + opts.timeout.toNanos();
            while (true) {
                if (opts.cancelFlag != null && opts.cancelFlag.get()) {
                    cancelled = true;
                    process.destroyForcibly();
                    break;
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    process.destroyForcibly();
                    break;
                }
            }
            if (timedOut || cancelled) {
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outTask.get(5, TimeUnit.SECONDS);
            errTask.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            cancelled = true;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("读取进程输出异常", e);
        } finally {
            ioPool.shutdownNow();
        }

        int exit;
        try {
            exit = process.exitValue();
        } catch (IllegalThreadStateException e) {
            exit = -1;
        }
        if (timedOut) {
            log.warn("外部命令超时（{}）: {}", opts.timeout, String.join(" ", command));
        }
        return new Result(exit, List.copyOf(stdout), List.copyOf(stderr), timedOut, cancelled);
    }

    private static void pump(java.io.InputStream stream, List<String> sink, Consumer<String> callback) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 限制内存：只保留最近 2000 行
                if (sink.size() < 2000) {
                    sink.add(line);
                }
                if (callback != null) {
                    try {
                        callback.accept(line);
                    } catch (Exception e) {
                        log.debug("输出回调异常: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程被杀时流会关闭，属正常退出路径
        }
    }
}
