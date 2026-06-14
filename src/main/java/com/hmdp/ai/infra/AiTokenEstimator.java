package com.hmdp.ai.infra;

import org.springframework.stereotype.Component;

@Component
public class AiTokenEstimator {

    public int estimate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int asciiWords = 0;
        int symbols = 0;
        boolean inAsciiWord = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                inAsciiWord = false;
                continue;
            }
            if (isCjk(ch)) {
                cjk++;
                inAsciiWord = false;
                continue;
            }
            if (isAsciiWord(ch)) {
                if (!inAsciiWord) {
                    asciiWords++;
                    inAsciiWord = true;
                }
                continue;
            }
            symbols++;
            inAsciiWord = false;
        }

        int estimated = cjk + asciiWords + (symbols + 1) / 2;
        return estimated <= 0 ? 1 : estimated;
    }

    private boolean isAsciiWord(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '_' || ch == '-';
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
