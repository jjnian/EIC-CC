package com.tuiyan.backend.service.extraction;

import com.tuiyan.backend.support.FileSniffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 音频处理：调 {@link AudioTranscriptionService} 把音频转成文本，再像文档一样 appendSection 进抽取上下文，
 * 后续整条链路（多模态抽取、salt 重写、持久化）无需任何改动即可吃到音频里的实体与关系。
 * <p>未配置 ASR / 转写失败 / 转写为空时，把该音频标记为 {@code skipped}（记 reason）而非抛错，
 * 保证一次上传里的其它文件照常抽取。
 * <p>{@code @Order(40)} 排在图片(30)之后：WAV 与 WebP 都以 {@code RIFF} 开头，先让图片 handler
 * （只认 RIFF…WEBP）放行，避免 WAV 被误吞。
 */
@Component
@Order(40)
public class AudioFileHandler implements SourceFileHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioFileHandler.class);

    private final AudioTranscriptionService transcription;

    public AudioFileHandler(AudioTranscriptionService transcription) {
        this.transcription = transcription;
    }

    @Override
    public boolean supports(FileProbe probe) {
        if (FileSniffer.isAudioMagic(probe.head())) return true;
        if (probe.lowerContentType().startsWith("audio/")) return true;
        String n = probe.lowerName();
        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a")
                || n.endsWith(".aac") || n.endsWith(".flac") || n.endsWith(".ogg")
                || n.endsWith(".oga") || n.endsWith(".opus") || n.endsWith(".amr")
                || n.endsWith(".wma") || n.endsWith(".webm");
    }

    @Override
    public void handle(UploadedFile f, String safeName, ExtractionContext ctx, Map<String, Object> meta) {
        if (!transcription.enabled()) {
            markSkipped(meta, "音频转写未启用（app.asr.enabled=false）");
            return;
        }
        long limit = transcription.maxBytes();
        if (f.size() > limit) {
            markSkipped(meta, "音频 " + safeName + " 超过 " + (limit / (1024 * 1024)) + " MB 限制");
            return;
        }
        ctx.step().emit("transcribing_audio", "正在转写音频 " + safeName + "…（时长越长越慢）");
        try {
            AudioTranscriptionService.TranscriptResult r =
                    transcription.transcribe(f.bytes(), safeName, f.contentType());
            String text = r.text();
            if (text == null || text.isBlank()) {
                markSkipped(meta, "音频转写为空（可能无可识别语音）");
                return;
            }
            meta.put("type", "audio");
            meta.put("chars", text.length());
            meta.put("model", r.model());
            if (r.durationSeconds() != null) meta.put("durationSec", Math.round(r.durationSeconds()));
            ctx.appendSection("# 音频转写 " + safeName, text);
        } catch (Exception e) {
            log.warn("[ASR] 音频 {} 转写失败: {}", safeName, e.toString());
            markSkipped(meta, "音频转写失败：" + e.getMessage());
        }
    }

    private static void markSkipped(Map<String, Object> meta, String reason) {
        meta.put("type", "skipped");
        meta.put("reason", reason);
    }
}
