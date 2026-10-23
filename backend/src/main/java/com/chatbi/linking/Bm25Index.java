package com.chatbi.linking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small in-memory Okapi BM25 index used for schema assets and few-shot examples. */
@Component
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final TextTokenizer tokenizer;

    public Bm25Index(TextTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public <T> List<Scored<T>> search(String query, List<Document<T>> documents, int limit) {
        if (documents.isEmpty()) {
            return List.of();
        }
        List<String> queryTokens = tokenizer.uniqueTokens(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> df = new HashMap<>();
        List<List<String>> docTokens = new ArrayList<>(documents.size());
        double totalLength = 0;

        for (Document<T> document : documents) {
            List<String> tokens = tokenizer.tokens(document.text());
            docTokens.add(tokens);
            totalLength += tokens.size();
            for (String token : new java.util.LinkedHashSet<>(tokens)) {
                df.merge(token, 1, Integer::sum);
            }
        }
        double avgLength = totalLength / documents.size();
        int n = documents.size();

        List<Scored<T>> scores = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            List<String> tokens = docTokens.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            double score = 0;
            for (String q : queryTokens) {
                Integer freq = tf.get(q);
                if (freq == null) {
                    continue;
                }
                int docFreq = df.getOrDefault(q, 0);
                double idf = Math.log(1 + (n - docFreq + 0.5) / (docFreq + 0.5));
                double lengthNorm = 1 - B + B * (tokens.size() / Math.max(avgLength, 1.0));
                score += idf * (freq * (K1 + 1)) / (freq + K1 * lengthNorm);
            }
            if (score > 0) {
                scores.add(new Scored<>(documents.get(i).id(), documents.get(i).payload(), score));
            }
        }
        scores.sort(Comparator.comparingDouble((Scored<T> scored) -> scored.score()).reversed());
        return scores.stream().limit(limit).toList();
    }

    public record Document<T>(String id, T payload, String text) {
    }

    public record Scored<T>(String id, T payload, double score) {
    }
}
