package com.chatbi.linking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextTokenizerTest {

    private final TextTokenizer tokenizer = new TextTokenizer();

    @Test
    void emitsCjkNgramsForBusinessTerms() {
        List<String> tokens = tokenizer.tokens("今年各区域销售额");

        assertThat(tokens).contains("销", "销售", "售额", "销售额", "区域");
    }

    @Test
    void dropsStopwordUnigramsButKeepsThemInsideNgrams() {
        List<String> tokens = tokenizer.tokens("各区域的");

        assertThat(tokens).doesNotContain("各", "的");
        assertThat(tokens).contains("各区", "区域");
    }

    @Test
    void lowercasesLatinAndSplitsOnPunctuation() {
        List<String> tokens = tokenizer.tokens("Sales Amount, ORDER-2025");

        assertThat(tokens).contains("sales", "amount", "order", "2025");
        assertThat(tokens).doesNotContain("Sales");
    }

    @Test
    void normalizeRemovesWhitespaceAndChinesePunctuation() {
        assertThat(tokenizer.normalize("  今年，销售额 ！ ")).isEqualTo("今年销售额");
        assertThat(tokenizer.normalize(null)).isEmpty();
    }

    @Test
    void blankInputProducesNoTokens() {
        assertThat(tokenizer.tokens("   ")).isEmpty();
        assertThat(tokenizer.uniqueTokens(null)).isEmpty();
    }

    @Test
    void uniqueTokensHasNoDuplicates() {
        List<String> tokens = tokenizer.uniqueTokens("销售销售额");

        assertThat(tokens).doesNotHaveDuplicates();
    }
}
