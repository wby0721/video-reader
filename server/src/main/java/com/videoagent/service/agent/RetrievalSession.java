package com.videoagent.service.agent;

import com.videoagent.dto.EvidenceHit;
import com.videoagent.dto.VideoChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Function;

/** 单次 Agent 运行内的查询缓存与已召回证据池。 */
public final class RetrievalSession {

    private final Long mediaId;
    private final int indexVersion;
    private final Map<String, List<EvidenceHit>> queryCache = new LinkedHashMap<>();
    private final Map<String, VideoChunk> evidencePool = new LinkedHashMap<>();
    private final Map<String, String> recallDiagnostics = new LinkedHashMap<>();

    public void recordDiagnostics(String query, String source) { recallDiagnostics.put(cacheKey(query), source); }
    public String diagnostics(String query) { return recallDiagnostics.getOrDefault(cacheKey(query), "UNKNOWN"); }
    private int retrievalQueries;

    public RetrievalSession(Long mediaId, int indexVersion) {
        this.mediaId = mediaId;
        this.indexVersion = indexVersion;
    }

    public List<EvidenceHit> cachedOrSearch(String query, Supplier<List<EvidenceHit>> search) {
        String key = cacheKey(query);
        List<EvidenceHit> cached = queryCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<EvidenceHit> hits = search.get();
        List<EvidenceHit> stable = hits == null ? List.of() : List.copyOf(hits);
        queryCache.put(key, stable);
        retrievalQueries++;
        return stable;
    }

    /** 把本轮所有缓存未命中的查询合并交给一次微批检索。 */
    public Map<String, List<EvidenceHit>> cachedOrSearchAll(
            List<String> queries,
            Function<List<String>, Map<String, List<EvidenceHit>>> batchSearch) {
        Map<String, String> missingByKey = new LinkedHashMap<>();
        if (queries != null) {
            for (String query : queries) {
                if (query == null || query.isBlank()) {
                    continue;
                }
                String key = cacheKey(query);
                if (!queryCache.containsKey(key)) {
                    missingByKey.putIfAbsent(key, query);
                }
            }
        }
        if (!missingByKey.isEmpty()) {
            List<String> missing = List.copyOf(missingByKey.values());
            Map<String, List<EvidenceHit>> searched = batchSearch.apply(missing);
            for (Map.Entry<String, String> entry : missingByKey.entrySet()) {
                List<EvidenceHit> hits = searched == null ? null : searched.get(entry.getValue());
                queryCache.put(entry.getKey(), hits == null ? List.of() : List.copyOf(hits));
            }
            retrievalQueries += missing.size();
        }
        Map<String, List<EvidenceHit>> result = new LinkedHashMap<>();
        if (queries != null) {
            for (String query : queries) {
                if (query != null && !query.isBlank()) {
                    result.put(query, queryCache.getOrDefault(cacheKey(query), List.of()));
                }
            }
        }
        return Map.copyOf(result);
    }

    public void addEvidence(VideoChunk chunk) {
        if (chunk != null && chunk.chunkId() != null && !chunk.chunkId().isBlank()) {
            evidencePool.putIfAbsent(chunk.chunkId(), chunk);
        }
    }

    public String cacheKey(String query) {
        return mediaId + ":" + indexVersion + ":" + normalize(query);
    }

    public int retrievalQueries() {
        return retrievalQueries;
    }

    public Long mediaId() {
        return mediaId;
    }

    public Map<String, List<EvidenceHit>> queryCache() {
        return Map.copyOf(queryCache);
    }

    public Map<String, VideoChunk> evidencePool() {
        return Map.copyOf(evidencePool);
    }

    private static String normalize(String query) {
        return (query == null ? "" : query)
                .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
