package com.chatbi.linking;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, dependency-free 512-dimension embedding based on character
 * n-gram feature hashing. It keeps the demo fully offline while preserving a
 * clean seam: replace this bean with a Spring AI EmbeddingModel-backed
 * implementation to use a hosted embedding model.
 */
@Component
public class HashingEmbeddingModel {

    public static final int DIMENSIONS = 512;

    private final TextTokenizer tokenizer;

    public HashingEmbeddingModel(TextTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        List<String> tokens = tokenizer.tokens(text);
        if (tokens.isEmpty()) {
            vector[0] = 1f;
            return vector;
        }

        Map<Integer, Double> counts = new HashMap<>();
        for (String token : tokens) {
            int h = hash(token);
            int index = Math.floorMod(h, DIMENSIONS);
            double sign = ((h >>> 16) & 1) == 0 ? 1.0 : -1.0;
            counts.merge(index, sign, Double::sum);
        }
        counts.forEach((index, value) -> vector[index] = value.floatValue());

        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    public String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }

    private int hash(String token) {
        // FNV-1a 32-bit, stable across JVMs.
        int hash = 0x811C9DC5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}
