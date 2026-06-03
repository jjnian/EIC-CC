package com.tuiyan.backend.support;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 文本字节宽容解码工具。 */
public final class TextDecoder {

    private TextDecoder() {}

    /**
     * 宽容解码：优先按 UTF-8 严格解码；遇到非 UTF-8 字节（常见于 Windows 下
     * GBK/GB18030 编码的中文 .txt）回退到 GB18030；仍失败则用替换式 UTF-8 兜底，
     * 乱码也好过整单失败。
     */
    public static String lenient(byte[] bytes) {
        for (Charset cs : new Charset[]{ StandardCharsets.UTF_8, Charset.forName("GB18030") }) {
            try {
                CharsetDecoder dec = cs.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return dec.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ignored) {
                // 尝试下一个编码
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
