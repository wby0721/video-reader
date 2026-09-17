package com.videoagent.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.videoagent.support.ExpandedRagEvaluation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.videoagent.support.ExpandedRagDataset.*;
import static com.videoagent.support.ExpandedRagEvaluation.*;
import static org.assertj.core.api.Assertions.*;

class ExpandedRagEvaluationTest {
    @Test void lowScoresRejectAndHintDoesNotUseGoldLabels() {
        assertThat(decide(Map.of("a", .02, "b", .04), .1).acceptedChunkIds()).isEmpty();
        assertThat(decide(Map.of("a", .02), .1).evidenceStatus()).isEqualTo("LOW_RELEVANCE");
        assertThat(decide(Map.of("a", .1, "b", .09), .1).acceptedChunkIds()).containsExactly("a");
        assertThat(decide(Map.of(), .1).acceptedChunkIds()).isEmpty();
        assertThatThrownBy(() -> decide(Map.of("a", Double.NaN), .1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decide(Map.of("a", -2.0), .1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decide(Map.of("a", 1.1), .1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void labelsAreCompleteAndSplitsAreVideoDisjoint() throws Exception {
        List<Annotation> labels = JSON.readValue(root().resolve("evaluator/annotations.json").toFile(), new TypeReference<>() {});
        assertThat(labels).hasSize(60);
        assertThat(labels.stream().filter(Annotation::related)).hasSize(40);
        Set<String> questions = new HashSet<>();
        var gold = new LinkedHashMap<String, Map<String,Integer>>();
        for (var v : load().videos()) {
            var local = labels.stream().filter(a -> a.videoId().equals(v.id())).toList();
            assertThat(local).hasSize(3);
            assertThat(local.stream().map(Annotation::split).distinct()).hasSize(1);
            var chunks = VideoChunkingService.chunk(context(v), v.userId(), v.contentHash(), RetrievalIndexService.INDEX_VERSION);
            for (var q : v.questions()) {
                assertThat(questions.add(q.id())).isTrue();
                var a = local.stream().filter(l -> l.questionId().equals(q.id())).findFirst().orElseThrow();
                var g = ExpandedRagEvaluation.gains(a, chunks);
                if (a.related()) assertThat(g).hasSizeBetween(4, 6);
                else assertThat(g).isEmpty();
                gold.put(q.id(),g);
            }
        }
        for (String split : List.of("calibration", "holdout")) {
            var local = labels.stream().filter(a -> a.split().equals(split)).toList();
            assertThat(local).hasSize(30);
            assertThat(local.stream().filter(a -> a.category().equals("hard-negative"))).hasSize(5);
            assertThat(local.stream().filter(a -> a.category().equals("off-topic"))).hasSize(5);
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(root().resolve("evaluator/groundtruth-chunks.json").toFile(), gold);
    }
}
