package com.tuiyan.backend.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * 视频抽音轨工具：调本机 ffmpeg 把视频转成单声道 16kHz 低码率 MP3，供 ASR 转写。
 * <ul>
 *   <li>未安装 ffmpeg 时 {@link #ffmpegAvailable()} 返回 false，调用方可回退为
 *       「把视频原样发给 ASR」（OpenAI Whisper 兼容端点原生接受 mp4/mpeg/webm）；</li>
 *   <li>压缩到语音级码率（48kbps mono）：1 小时会议音轨约 20MB，能贴着 Whisper 25MB 上限过。</li>
 * </ul>
 */
public final class VideoAudioExtractor {

    private static final Logger log = LoggerFactory.getLogger(VideoAudioExtractor.class);

    /** ffmpeg 转码超时（长视频转码慢，给足余量）。 */
    private static final int FFMPEG_TIMEOUT_SEC = 300;

    private static volatile Boolean available;

    private VideoAudioExtractor() {}

    /** 本机是否可用 ffmpeg（首次探测后缓存）。 */
    public static boolean ffmpegAvailable() {
        Boolean a = available;
        if (a != null) return a;
        synchronized (VideoAudioExtractor.class) {
            if (available != null) return available;
            try {
                Process p = new ProcessBuilder("ffmpeg", "-version")
                        .redirectErrorStream(true).start();
                boolean done = p.waitFor(10, TimeUnit.SECONDS);
                available = done && p.exitValue() == 0;
                if (!done) p.destroyForcibly();
            } catch (Exception e) {
                available = false;
            }
            log.info("[video] ffmpeg {}", available ? "可用（视频将抽音轨压缩后转写）" : "不可用（视频将原样发给 ASR，受其大小/格式限制）");
            return available;
        }
    }

    /**
     * 抽取音轨：视频字节 → 单声道 16kHz 48kbps MP3 字节。
     * @throws IOException ffmpeg 失败 / 超时 / 无音轨
     */
    public static byte[] extractAudio(byte[] video, String name) throws IOException {
        Path dir = Files.createTempDirectory("vid-asr-");
        Path in = dir.resolve("in" + safeExt(name));
        Path out = dir.resolve("out.mp3");
        try {
            Files.write(in, video);
            Process p = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", in.toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-b:a", "48k",
                    out.toString())
                    .redirectErrorStream(true)
                    .start();
            // 读掉输出防止缓冲区堵死进程
            byte[] ffLog = p.getInputStream().readAllBytes();
            boolean done;
            try {
                done = p.waitFor(FFMPEG_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new IOException("视频抽音轨被中断");
            }
            if (!done) {
                p.destroyForcibly();
                throw new IOException("视频抽音轨超时（>" + FFMPEG_TIMEOUT_SEC + "s）");
            }
            if (p.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) {
                String tail = new String(ffLog);
                if (tail.length() > 400) tail = tail.substring(tail.length() - 400);
                throw new IOException("ffmpeg 抽音轨失败（可能视频无音轨）: " + tail.strip());
            }
            return Files.readAllBytes(out);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignore) { /* 尽力清理 */ }
                });
            } catch (IOException ignore) { /* 尽力清理 */ }
        }
    }

    /** 输入临时文件保留原扩展名（帮 ffmpeg 识别容器），仅接受安全字符。 */
    private static String safeExt(String name) {
        if (name == null) return ".mp4";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return ".mp4";
        String ext = name.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".mp4";
    }
}
