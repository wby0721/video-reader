package com.videoagent.service.retrieval;

import java.util.List;

/** 一次改写后的三路查询输入。 */
public record HybridQuery(
        String originalQuery,
        String semanticQuery,
        List<String> keywords,
        List<String> visualKeywords
) {
    public HybridQuery {
        originalQuery = value(originalQuery);
        semanticQuery = value(semanticQuery).isBlank() ? originalQuery : semanticQuery;
        keywords = keywords == null ? List.of() : keywords;
        visualKeywords = visualKeywords == null ? List.of() : visualKeywords;
    }

    public String bm25Query() {
        return keywords.isEmpty() ? originalQuery : String.join(" ", keywords);
    }

    public String ocrQuery() {
        return visualKeywords.isEmpty() ? bm25Query() : String.join(" ", visualKeywords);
    }

    private static String value(String text) {
        return text == null ? "" : text.trim();
    }
}
