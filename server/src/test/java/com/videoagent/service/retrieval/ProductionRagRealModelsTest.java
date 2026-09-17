package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.config.AppProperties;
import com.videoagent.dto.*;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.agent.EvidencePackService;
import com.videoagent.service.agent.RetrievalSession;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.support.RagFixtureLoader;
import com.videoagent.utils.EmbeddingClient;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Original 20/40-minute fixtures and original six questions, unchanged.
 * Real retrieval at all three production consumers. No generation/rewrite LLM or DB.
 */
@EnabledIfSystemProperty(named="rag.production-real", matches="true")
class ProductionRagRealModelsTest {
    @Test void originalFixturesReachChatAgentAndGlobalProductionConsumers() throws Exception {
        var json = new ObjectMapper();
        var props = new AppProperties(null,null,null,new AppProperties.Qdrant("http://127.0.0.1:6333"),
                null,new AppProperties.Retrieval(null,.5,false),
                new AppProperties.Ai(null,new AppProperties.Ai.Embedding("http://127.0.0.1:8000","","bge-m3",1024),
                        null,null,new AppProperties.Ai.Reranker("http://127.0.0.1:8003")),null,null,null);
        var report = new LinkedHashMap<String,Object>();
        report.put("startedAt",Instant.now().toString());
        report.put("scope","Real BGE/Qdrant/Lucene/reranker + production chat/Agent/global evidence; prewritten independent queries; no answer or planner LLM; mocked DB boundary");
        for (int port : List.of(8000,8003)) {
            var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/health"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var health = json.readTree(response.body());
            assertThat(health.path("ready").asBoolean()).isTrue();
            report.put("health"+port,health);
        }
        var vectors = new QdrantVectorStore(props);
        vectors.ensureCollection();
        var embedding = new EmbeddingClient(props);
        var reranker = new CountingReranker(props);
        var cp = mock(CheckpointService.class);
        var llm = mock(LlmProvider.class);
        var repo = mock(MediaFileRepository.class);
        var policy = new RelevancePolicy(props);
        var chat = new ChatEvidenceService(policy);
        var videos = RagFixtureLoader.loadGoldenSet(json).videos();
        List<Object> results = new ArrayList<>();
        List<MediaFile> media = new ArrayList<>();
        try (var bm25 = new Bm25IndexService(new ByteBuffersDirectory())) {
            var indexes = new RetrievalIndexService(vectors,bm25,embedding,new ChunkEnricher(json),cp,llm);
            var hybrid = new HybridRetrievalService(vectors,bm25,embedding,new WeightedRrfFusion(),reranker);
            var retrieval = new VideoEvidenceRetrievalService(indexes,hybrid,new QueryRewriter(json),llm);
            var agent = new EvidencePackService(retrieval,indexes,policy);
            for (var video : videos) {
                long uid=983000L, mid=video.mediaId()+1_000_000L;
                String hash="production-smoke-"+video.contentHash();
                var fixture=video.fixture().equals("20min") ? RagFixtureLoader.load20Minutes(json) : RagFixtureLoader.load40Minutes(json);
                var context=fixture.align(Long.toString(mid),"");
                var m=new MediaFile();m.setId(mid);m.setUserId(uid);m.setContentHash(hash);
                m.setTitle(video.title());m.setFilename(video.fixture()+".mp4");m.setStatus(MediaFile.STATUS_CONTEXT_READY);media.add(m);
                when(cp.loadVideoContext(mid)).thenReturn(Optional.of(context));
                var chunks=indexes.indexForSearch(mid,hash,context,uid);
                assertThat(chunks).hasSize(video.fixture().equals("20min")?16:32);
                assertThat(chunks).allMatch(c->c.embedding()!=null && c.embedding().size()==1024);
                assertThat(vectors.search(chunks.getFirst().embedding(),25,983001L,hash,2)).isEmpty();
                for (var q : video.questions()) {
                    var terms=Arrays.stream(q.standaloneQuery().split("\\s+")).toList();
                    var prepared=retrieval.searchPrepared(mid,hash,context,
                            new HybridQuery(q.userQuestion(),q.standaloneQuery(),terms,terms),5,uid);
                    assertThat(prepared.denseSource()).isEqualTo("QDRANT");
                    assertThat(prepared.hits()).hasSize(5).allMatch(h->h.scoreType()==EvidenceHit.ScoreType.RERANKER_SIGMOID);
                    var pack=chat.prepare(mid,prepared);
                    assertThat(pack.retrieval().degraded()).isFalse();
                    for (var interval : pack.evidence()) {
                        var selected=chunks.stream().filter(c->interval.chunkIds().contains(c.chunkId())).toList();
                        assertThat(interval.startMs()).isEqualTo(selected.stream().mapToLong(c->EvidenceBounds.of(c).startMs()).min().orElseThrow());
                        assertThat(interval.endMs()).isEqualTo(selected.stream().mapToLong(c->EvidenceBounds.of(c).endMs()).max().orElseThrow());
                    }
                    results.add(Map.of("entry","video-chat","fixture",video.fixture(),"question",q.userQuestion(),
                            "retrieval",pack.retrieval(),"hits",pack.hits(),"evidence",pack.evidence()));
                }
                var tasks=video.questions().stream().map(RagFixtureLoader.GoldenQuestion::standaloneQuery).toList();
                var session=new RetrievalSession(mid,2);
                var pack=agent.build(session,hash,context,chunks,tasks,List.of(),uid);
                assertThat(pack.items()).isNotEmpty();
                assertThat(pack.retrieval().values()).allMatch(a->!a.degraded());
                int batches=reranker.batches;
                var cached=agent.build(session,hash,context,chunks,tasks,List.of(chunks.getFirst().startTime()),uid);
                assertThat(reranker.batches).isEqualTo(batches);
                assertThat(session.retrievalQueries()).isEqualTo(3);
                results.add(Map.of("entry","video-analysis","fixture",video.fixture(),"pack",cached,
                        "prompt",cached.toPromptText(),"retrievalQueries",session.retrievalQueries()));
            }
            when(repo.findByUserIdOrderByCreatedAtDesc(983000L)).thenReturn(media);
            var global=new GlobalKnowledgeSearchService(repo,cp,indexes,hybrid,policy);
            for (var video : videos) {
                String query=video.questions().getFirst().standaloneQuery();
                var response=global.searchDetailed(983000L,query,10);
                assertThat(response.hits()).isNotEmpty().hasSizeLessThanOrEqualTo(6);
                for (var m : media) assertThat(response.hits().stream().filter(h->h.mediaId().equals(m.getId()))).hasSizeLessThanOrEqualTo(3);
                assertThat(response.retrieval().degraded()).isFalse();
                assertThat(json.valueToTree(response.hits().getFirst()).has("score")).isFalse();
                results.add(Map.of("entry","cross-video-search","query",query,"response",response));
            }
            verifyNoInteractions(llm);
        }
        assertThat(reranker.singles).isEqualTo(8);
        assertThat(reranker.batches).isEqualTo(2);
        report.put("results",results);
        report.put("realRerankerSingleRequests",reranker.singles);
        report.put("realRerankerBatchRequests",reranker.batches);
        report.put("completedAt",Instant.now().toString());
        var output=Path.of("target/rag-test-reports/production-integration.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        System.out.println("Production RAG integration report: "+output.toAbsolutePath());
    }
    static class CountingReranker extends RerankerClient {
        int singles,batches;
        CountingReranker(AppProperties p){super(p);}
        @Override public List<RankedDocument> rerank(String q,List<DocumentInput> docs,int topN) {
            var result=super.rerank(q,docs,topN);singles++;return result;
        }
        @Override public List<List<RankedDocument>> rerankBatch(List<BatchInput> inputs) {
            var result=super.rerankBatch(inputs);batches++;return result;
        }
    }
}
