package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.config.AppProperties;
import com.videoagent.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.videoagent.dto.RetrievalAssessment.Status.*;

class RelevancePolicyTest {
    private EvidenceHit hit(double score, EvidenceHit.ScoreType type) {
        return new EvidenceHit(0,1000,"id","summary",List.of(),score,List.of(),"QDRANT",type);
    }
    @Test void defaultWarnsWithoutRejectingAndStrictModeFiltersOnlyRerankerScores() {
        var low = List.of(hit(.1,EvidenceHit.ScoreType.RERANKER_SIGMOID));
        var warning = new RelevancePolicy().evaluate(low);
        assertThat(warning.hits()).hasSize(1);
        assertThat(warning.assessment().status()).isEqualTo(LOW_RELEVANCE);
        var strict = new RelevancePolicy(new AppProperties.Retrieval(null,.5,true));
        assertThat(strict.evaluate(low).hits()).isEmpty();
        var fallback = strict.evaluate(List.of(hit(.01,EvidenceHit.ScoreType.RRF)));
        assertThat(fallback.hits()).hasSize(1);
        assertThat(fallback.assessment().status()).isEqualTo(DEGRADED);
        assertThat(strict.evaluate(List.of(),"EMBEDDING_UNAVAILABLE").assessment().status()).isEqualTo(DEGRADED);
        assertThat(strict.evaluate(List.of()).assessment().status()).isEqualTo(NO_EVIDENCE);
    }
    @Test void oldCheckpointScoreIsNotAssumedToBeRerankerScore() throws Exception {
        var h = new ObjectMapper().readValue("""
                {"startMs":0,"endMs":1000,"chunkId":"old","summary":"old","keywords":[],
                 "score":0.99,"matchedTerms":[],"source":"QDRANT"}
                """, EvidenceHit.class);
        assertThat(h.scoreType()).isEqualTo(EvidenceHit.ScoreType.UNKNOWN);
        assertThat(new RelevancePolicy().evaluate(List.of(h)).assessment().status()).isEqualTo(DEGRADED);
    }
}
