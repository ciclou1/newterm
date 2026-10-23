package com.chatbi.linking;

import com.chatbi.config.ChatBiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, dependency-free 512-dimension embedding based on character
 * n-gram feature hashing. It keeps the demo fully offline while preserving a
 * clean seam: replace this bean with a Spring AI EmbeddingModel-backed
 * implementation to use a hosted embedding model.
 *
 * <p>These are <strong>lexical</strong> feature-hash vectors, not semantic
 * embeddings: two paraphrases only score highly when they share characters.
 * The verified deepseek gateway did not expose {@code /v1/embeddings}, which is
 * why the hash provider remains the default.</p>
 */
@Slf4j
@Component
public class HashingEmbeddingModel {

    public static final int DIMENSIONS = 512;

    private final TextTokenizer tokenizer;

    public HashingEmbeddingModel(TextTokenizer tokenizer, ChatBiProperties properties) {
        this.tokenizer = tokenizer;
        ChatBiProperties.Embedding embedding = properties.getEmbedding();
        if (!"hash".equalsIgnoreCase(embedding.getProvider())) {
            throw new IllegalStateException("chatbi.embedding.provider=" + embedding.getProvider()
                    + " 尚未实现：当前只有 hash provider。若改用远程 embedding，请实现 "
                    + "EmbeddingModel 适配器并补充向量维度迁移。");
        }
        if (embedding.getDimensions() != DIMENSIONS) {
            throw new IllegalStateException("chatbi.embedding.dimensions=" + embedding.getDimensions()
                    + " 与数据库 vector(" + DIMENSIONS + ") 不一致。修改维度需要新增 Flyway 迁移，"
                    + "重建 semantic_embedding.embedding 列并全量重建索引。");
        }
        log.info("Embedding provider=hash dimensions={} (lexical feature hashing, not a semantic model)",
                DIMENSIONS);
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
