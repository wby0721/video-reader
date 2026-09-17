package com.videoagent.service.retrieval;

import com.videoagent.support.ExpandedRagDataset;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static com.videoagent.support.ExpandedRagDataset.*;

class ExpandedRagDatasetTest {
    @Test void processFilesMatchProductionAndHaveNoEvaluationLabels() throws Exception {
        var manifest = load();
        assertThat(manifest.videos()).hasSize(20);
        Set<String> ids = new HashSet<>();
        var enricher = new ChunkEnricher(JSON);
        for (var v : manifest.videos()) {
            var asr = asr(v); var ocr = ocr(v);
            assertThat(v.questions()).hasSize(3);
            assertThat(asr).hasSize(60);
            assertThat(ocr).hasSize(120);
            assertThat(asr.getFirst().startMs()).isZero();
            assertThat(asr.getLast().endMs()).isEqualTo(1_200_000);
            long previous = 0;
            for (var seg : asr) {
                assertThat(seg.startMs()).isEqualTo(previous);
                assertThat(seg.endMs()).isGreaterThan(seg.startMs());
                previous = seg.endMs();
            }
            for (int minute = 0; minute < 20; minute++) {
                int from = minute*3;
                assertThat(asr.subList(from, from+3).stream().mapToInt(s -> s.text().length()).sum())
                    .as(v.id()+" minute "+minute).isBetween(300, 550);
            }
            assertThat(ocr).allMatch(f -> f.timestampMs()>=0 && f.timestampMs()<v.durationMs() && f.texts().size()>=6);
            var folder = root().resolve(v.id());
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(folder.resolve("ingest-asr.json")));
            digest.update(Files.readAllBytes(folder.resolve("ingest-ocr.json")));
            assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(v.contentHash());
            var context = context(v);
            var chunks = VideoChunkingService.chunk(context, v.userId(), v.contentHash(), RetrievalIndexService.INDEX_VERSION)
                .stream().map(c -> { var e = enricher.enrich(c.transcript(), c.visualTexts(), null);
                    return c.withEnrichment(e.summary(), e.keywords()); }).toList();
            assertThat(chunks).hasSize(16).allMatch(c -> !c.keywords().isEmpty());
            for (var c : chunks) assertThat(ids.add(c.chunkId())).isTrue();
            // Derived process artifacts, from production alignment/chunk/enrichment, not hand-written DTOs.
            JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("video-context.json").toFile(), context);
            JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("chunks-keywords.json").toFile(), chunks);
        }
        String inference = Files.readString(root().resolve("manifest.json"));
        assertThat(inference).doesNotContain("related", "category", "supportIntervals", "split", "reason");
    }
}
