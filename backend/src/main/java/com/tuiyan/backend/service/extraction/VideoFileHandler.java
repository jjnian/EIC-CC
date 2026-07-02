package com.tuiyan.backend.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 视频处理：抽音轨（本机 ffmpeg，压成语音级 MP3）→ ASR 转写 → 正文进抽取上下文。
 * 培训录像 / 会议录屏由此变成可建图的文本来源。
 * <p>未装 ffmpeg 时回退为把视频原样发给 ASR（OpenAI Whisper 兼容端点原生接受 mp4/mpeg/webm），
 * 受 ASR 大小上限约束；两条路都走不通时标记 skipped（记 reason），不影响同批其它文件。
 * <p>{@code @Order(45)} 排在音频(40)之后：.webm 等音频/视频两可的容器优先按音频直发。
 */
@Component
@Order(45)
public class VideoFileHandler implements SourceFileHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoFileHandler.class);

    /** 转写正文写入 data_source.extra_json 的字符上限（与音频一致）。 */
    private static final int TRANSCRIPT_CHAR_BUDGET = 100_000;
    /** 视频原始字节上限：与 multipart 上限（默认 200MB，UPLOAD_MAX_FILE_SIZE）对齐。 */
    static final long VIDEO_BYTES_LIMIT = 200L * 1024 * 1024;

    private final AudioTranscriptionService transcription;

    public VideoFileHandler(AudioTranscriptionService transcription) {
        this.transcription = transcription;
    }

    @Override
    public boolean supports(FileProbe probe) {
        if (probe.lowerContentType().startsWith("video/")) return true;
        if (isMp4Magic(probe.head())) return true;
        String n = probe.lowerName();
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mov")
                || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mpg")
                || n.endsWith(".mpeg") || n.endsWith(".wmv") || n.endsWith(".flv");
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) {
        if (!transcription.enabled()) {
            markSkipped(meta, "音频转写未启用（app.asr.enabled=false），无法转写视频");
            return;
        }
        if (f.size() > VIDEO_BYTES_LIMIT) {
            markSkipped(meta, "视频 " + safeName + " 超过 " + (VIDEO_BYTES_LIMIT / (1024 * 1024)) + " MB 上限");
            return;
        }

        byte[] audio;
        String audioName;
        String audioMime;
        if (VideoAudioExtractor.ffmpegAvailable()) {
            ctx.step().emit("extracting_audio", "正在从视频 " + safeName + " 抽取音轨…");
            try {
                audio = VideoAudioExtractor.extractAudio(f.bytes(), safeName);
                audioName = safeName + ".mp3";
                audioMime = "audio/mpeg";
            } catch (Exception e) {
                log.warn("[video] {} 抽音轨失败: {}", safeName, e.toString());
                markSkipped(meta, "视频抽音轨失败：" + e.getMessage());
                return;
            }
        } else if (f.size() <= transcription.maxBytes()) {
            // 无 ffmpeg：视频原样发给 ASR（OpenAI Whisper 兼容端点接受 mp4/mpeg/webm）
            audio = f.bytes();
            audioName = safeName;
            audioMime = f.contentType();
        } else {
            markSkipped(meta, "视频超过转写大小上限 " + (transcription.maxBytes() / (1024 * 1024))
                    + " MB 且未安装 ffmpeg（无法抽音轨压缩），请安装 ffmpeg 或先转成音频");
            return;
        }
        if (audio.length > transcription.maxBytes()) {
            markSkipped(meta, "抽出的音轨仍超过转写大小上限 "
                    + (transcription.maxBytes() / (1024 * 1024)) + " MB，请剪辑分段后重试");
            return;
        }

        ctx.step().emit("transcribing_video", "正在转写视频 " + safeName + " 的音轨…（时长越长越慢）");
        try {
            AudioTranscriptionService.TranscriptResult r = transcription.transcribe(audio, audioName, audioMime);
            String text = r.text();
            if (text == null || text.isBlank()) {
                markSkipped(meta, "视频音轨转写为空（可能无可识别语音）");
                return;
            }
            meta.put("type", "video");
            meta.put("chars", text.length());
            meta.put("model", r.model());
            if (r.durationSeconds() != null) meta.put("durationSec", Math.round(r.durationSeconds()));
            // 与音频同法：转写正文落 data_source.extra_json，作为视频内容的可检索留存
            meta.put("transcript", capTranscript(text));
            ctx.appendSection(safeName, "# 视频转写 " + safeName, text);
        } catch (Exception e) {
            log.warn("[video] {} 转写失败: {}", safeName, e.toString());
            markSkipped(meta, "视频转写失败：" + e.getMessage());
        }
    }

    /** MP4/MOV 家族容器 magic：offset 4 起为 "ftyp"。 */
    private static boolean isMp4Magic(byte[] head) {
        return head != null && head.length >= 8
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
    }

    private static void markSkipped(Map<String, Object> meta, String reason) {
        meta.put("type", "skipped");
        meta.put("reason", reason);
    }

    private static String capTranscript(String text) {
        if (text.length() <= TRANSCRIPT_CHAR_BUDGET) return text;
        return text.substring(0, TRANSCRIPT_CHAR_BUDGET) + "\n[…truncated…]";
    }
}
