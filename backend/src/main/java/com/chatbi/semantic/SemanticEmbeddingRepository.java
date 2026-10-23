package com.chatbi.semantic;

import com.chatbi.linking.HashingEmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SemanticEmbeddingRepository {

    private final JdbcTemplate jdbc;
    private final HashingEmbeddingModel embeddingModel;

    public SemanticEmbeddingRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
                                       HashingEmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    public Map<String, String> hashes(String ownerType) {
        Map<String, String> result = new HashMap<>();
        jdbc.query("SELECT owner_key, content_hash FROM semantic_embedding WHERE owner_type = ?",
                rs -> {
                    result.put(rs.getString("owner_key"), rs.getString("content_hash"));
                }, ownerType);
        return result;
    }

    public void upsert(String ownerType, String ownerKey, String content, String hash, String metadataJson) {
        String vector = embeddingModel.toPgVector(embeddingModel.embed(content));
        jdbc.update("""
                INSERT INTO semantic_embedding (owner_type, owner_key, content, content_hash, metadata, embedding, updated_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), now())
                ON CONFLICT (owner_type, owner_key) DO UPDATE SET
                    content = EXCLUDED.content,
                    content_hash = EXCLUDED.content_hash,
                    metadata = EXCLUDED.metadata,
                    embedding = EXCLUDED.embedding,
                    updated_at = now()
                """, ownerType, ownerKey, content, hash, metadataJson, vector);
    }

    public void delete(String ownerType, String ownerKey) {
        jdbc.update("DELETE FROM semantic_embedding WHERE owner_type = ? AND owner_key = ?", ownerType, ownerKey);
    }

    public List<VectorHit> search(String ownerType, String query, int limit) {
        String vector = embeddingModel.toPgVector(embeddingModel.embed(query));
        return jdbc.query("""
                SELECT owner_key, content, metadata::text AS metadata, 1 - (embedding <=> CAST(? AS vector)) AS score
                FROM semantic_embedding
                WHERE owner_type = ?
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, (rs, rowNum) -> new VectorHit(
                        rs.getString("owner_key"),
                        rs.getString("content"),
                        rs.getString("metadata"),
                        rs.getDouble("score")), vector, ownerType, vector, limit);
    }

    public List<String> keys(String ownerType) {
        return jdbc.queryForList("SELECT owner_key FROM semantic_embedding WHERE owner_type = ?",
                String.class, ownerType);
    }

    public List<VectorHit> searchAcrossTypes(String query, int limit) {
        String vector = embeddingModel.toPgVector(embeddingModel.embed(query));
        return jdbc.query("""
                SELECT owner_type, owner_key, content, metadata::text AS metadata,
                       1 - (embedding <=> CAST(? AS vector)) AS score
                FROM semantic_embedding
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, (rs, rowNum) -> new VectorHit(
                        rs.getString("owner_key"), rs.getString("content"),
                        rs.getString("metadata"), rs.getDouble("score")), vector, vector, limit);
    }

    public record VectorHit(String key, String content, String metadata, double score) {
    }
}
