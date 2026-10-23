package com.chatbi.linking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(new TextTokenizer());

    @Test
    void producesDeterministicUnitVectors() {
        float[] first = model.embed("今年各区域销售额");
        float[] second = model.embed("今年各区域销售额");

        assertThat(first).hasSize(HashingEmbeddingModel.DIMENSIONS);
        assertThat(first).containsExactly(second);
        assertThat(norm(first)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void similarQuestionsScoreHigherThanUnrelatedOnes() {
        float[] base = model.embed("今年各区域的销售额");
        float[] similar = model.embed("今年区域销售金额");
        float[] unrelated = model.embed("设备维修工单数量");

        assertThat(cosine(base, similar)).isGreaterThan(cosine(base, unrelated));
    }

    @Test
    void emptyTextStillReturnsAUsableVector() {
        float[] vector = model.embed("");

        assertThat(vector).hasSize(HashingEmbeddingModel.DIMENSIONS);
        assertThat(vector[0]).isEqualTo(1f);
    }

    @Test
    void pgVectorLiteralIsBracketedAndFixedPrecision() {
        String literal = model.toPgVector(model.embed("销售额"));

        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal.split(",")).hasSize(HashingEmbeddingModel.DIMENSIONS);
        assertThat(literal).doesNotContain("E", "Infinity", "NaN");
    }

    private double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
