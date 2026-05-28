package com.tuiyan.backend.service.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本分块工具：将长文本按指定 token 预算拆分为重叠块，供向量索引使用。
 * <p>估算规则：1 token ≈ 2 个中文字符 或 4 个英文字符。
 */
public final class TextChunker {

    private TextChunker() {}

    /** 段落分隔符：连续两个以上换行 */
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n{2,}");
    /** 句子分隔符：句号/问号/感叹号 + 可选空白 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。？！.?!])\\s*");

    /**
     * 将文本拆分为大小约 {@code chunkSize} token 的块，相邻块重叠 {@code overlap} token。
     *
     * @param text      待拆分文本
     * @param chunkSize 每块的目标 token 数（估算）
     * @param overlap   相邻块重叠 token 数
     * @return 文本块列表，不含空白块
     */
    public static List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int chunkChars = tokenToChars(chunkSize);
        int overlapChars = tokenToChars(overlap);

        // 先按段落拆分
        String[] paragraphs = PARAGRAPH_SPLIT.split(text);
        List<String> segments = new ArrayList<>();
        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() <= chunkChars) {
                segments.add(trimmed);
            } else {
                // 段落过长，按句子进一步拆分
                String[] sentences = SENTENCE_SPLIT.split(trimmed);
                for (String s : sentences) {
                    String st = s.trim();
                    if (!st.isEmpty()) segments.add(st);
                }
            }
        }

        // 将 segments 合并成目标大小的 chunk
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String seg : segments) {
            if (current.isEmpty()) {
                current.append(seg);
            } else if (current.length() + 1 + seg.length() <= chunkChars) {
                current.append("\n").append(seg);
            } else {
                // 当前块已满，保存并开始新块
                chunks.add(current.toString());
                // 计算重叠部分：取当前块末尾的 overlapChars 个字符
                String prev = current.toString();
                current = new StringBuilder();
                if (overlapChars > 0 && prev.length() > overlapChars) {
                    String overlapText = prev.substring(prev.length() - overlapChars);
                    current.append(overlapText).append("\n");
                }
                current.append(seg);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    /**
     * 估算 token 数对应的字符数。
     * <p>混合文本折中取 1 token ≈ 3 字符（中文偏 2、英文偏 4 的折中值）。
     */
    static int tokenToChars(int tokens) {
        return Math.max(1, tokens * 3);
    }

    /**
     * 估算文本的 token 数。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 3);
    }
}
