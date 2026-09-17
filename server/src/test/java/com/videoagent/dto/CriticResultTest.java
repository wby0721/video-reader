package com.videoagent.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CriticResultTest {

    @Test
    void infersIncrementalActionForOlderOrIncompleteModelOutput() {
        CriticResult search = new CriticResult(false, List.of(), List.of(), List.of(),
                List.of(), null, List.of("补搜部署限制"));
        CriticResult timestamp = new CriticResult(false, List.of(), List.of(), List.of(),
                List.of(60_000L), null, List.of());
        CriticResult invalidSearch = new CriticResult(false, List.of(), List.of(), List.of(),
                List.of(), RetrievalAction.SEARCH, List.of());

        assertThat(search.retrievalAction()).isEqualTo(RetrievalAction.SEARCH);
        assertThat(timestamp.retrievalAction()).isEqualTo(RetrievalAction.ADD_TIMESTAMP);
        assertThat(invalidSearch.retrievalAction()).isEqualTo(RetrievalAction.REUSE);
    }
}
