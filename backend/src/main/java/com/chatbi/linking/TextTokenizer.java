package com.chatbi.linking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight tokenizer tuned for Chinese business questions. It avoids a
 * native segmenter while still producing useful unigram / bigram / trigram
 * features for BM25 and for the local hashing embedding.
 */
@Component
public class TextTokenizer {

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "及", "或", "按", "各", "每", "请", "帮", "我",
            "一下", "看看", "查", "查询", "统计", "分析", "数据", "情况", "多少", "什么", "怎么",
            "如何", "哪", "哪些", "这个", "那个", "有", "吗", "呢", "啊", "把", "对", "给", "为"
    );

    public List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        List<String> cjk = new ArrayList<>();
        StringBuilder latin = new StringBuilder();

        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            int width = Character.charCount(cp);
            if (isCjk(cp)) {
                flushLatin(latin, tokens);
                String ch = new String(Character.toChars(cp));
                cjk.add(ch);
                if (!STOPWORDS.contains(ch)) {
                    tokens.add(ch);
                }
            } else if (Character.isLetterOrDigit(cp)) {
                latin.appendCodePoint(cp);
            } else {
                flushLatin(latin, tokens);
            }
            i += width;
        }
        flushLatin(latin, tokens);

        // Character n-grams carry most of the signal for Chinese business terms.
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= cjk.size(); i++) {
                String gram = String.join("", cjk.subList(i, i + n));
                if (!STOPWORDS.contains(gram)) {
                    tokens.add(gram);
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    public List<String> uniqueTokens(String text) {
        return new ArrayList<>(new LinkedHashSet<>(tokens(text)));
    }

    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》]+", "")
                .trim();
    }

    private void flushLatin(StringBuilder latin, Set<String> tokens) {
        if (!latin.isEmpty()) {
            String word = latin.toString();
            if (word.length() > 1 || Character.isDigit(word.charAt(0))) {
                tokens.add(word);
            }
            latin.setLength(0);
        }
    }

    private boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
    }
}
