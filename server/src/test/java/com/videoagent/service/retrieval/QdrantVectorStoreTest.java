package com.videoagent.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantVectorStoreTest {

    @Test
    void searchRequestBody_filtersByUserAndContentHash() {
        Map<String, Object> body = QdrantVectorStore.searchRequestBody(
                List.of(1f, 0f), 25, 7L, "hash-a", 2);

        assertThat(body).containsEntry("limit", 25);
        Map<?, ?> filter = (Map<?, ?>) body.get("filter");
        List<?> must = (List<?>) filter.get("must");
        assertThat(must).isEqualTo(List.of(
                Map.of("key", "userId", "match", Map.of("value", 7L)),
                Map.of("key", "contentHash", "match", Map.of("value", "hash-a")),
                Map.of("key", "indexVersion", "match", Map.of("value", 2))));
    }

    @Test
    void searchRequestBody_globalScopeStillRequiresUserFilter() {
        Map<String, Object> body = QdrantVectorStore.searchRequestBody(
                List.of(1f), 10, 9L, null, 2);

        Map<?, ?> filter = (Map<?, ?>) body.get("filter");
        assertThat((List<?>) filter.get("must")).isEqualTo(List.of(
                Map.of("key", "userId", "match", Map.of("value", 9L)),
                Map.of("key", "indexVersion", "match", Map.of("value", 2))));
    }

    @Test
    void pointId_isStableWithinUserButDifferentAcrossUsers() {
        String a1 = QdrantVectorStore.pointId(7L, "same-content", 2, 3);
        String a2 = QdrantVectorStore.pointId(7L, "same-content", 2, 3);
        String b = QdrantVectorStore.pointId(8L, "same-content", 2, 3);
        String nextVersion = QdrantVectorStore.pointId(7L, "same-content", 3, 3);

        assertThat(a1).isEqualTo(a2);
        assertThat(b).isNotEqualTo(a1);
        assertThat(nextVersion).isNotEqualTo(a1);
    }
}
