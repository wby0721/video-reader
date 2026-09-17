package com.videoagent.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.videoagent.config.AppProperties;
import com.videoagent.dto.*;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.service.eval.RagRetrievalMetrics;
import com.videoagent.support.ExpandedRagDataset.*;
import com.videoagent.support.ExpandedRagEvaluation.*;
import com.videoagent.utils.EmbeddingClient;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.ToDoubleFunction;
import static com.videoagent.support.ExpandedRagDataset.*;
import static com.videoagent.support.ExpandedRagEvaluation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Production ingest alignment -> indexing -> facade retrieval -> real EvidencePack.
 * Tracking subclasses call super, NEVER fabricate results or retry stages for the trace.
 * Only database checkpoint and unavailable generation LLM are isolated at test boundaries.
 */
@EnabledIfSystemProperty(named="rag.expanded-real", matches="true")
class ExpandedRagRealModelsTest {
    private final Path output = Path.of("target/rag-test-reports/expanded-v1");
    private final ChatEvidenceService packs = new ChatEvidenceService();
    private final List<Run> runs = new ArrayList<>();
    record Run(Video video, Question question, List<VideoChunk> chunks, List<EvidenceHit> hits,
               Map<String,Double> scores, List<String> dense, List<String> bm25, List<String> ocr,
               List<String> fused, long queryMs) {}

    @Test void sixtyQueriesWithRealServicesAndHeldOutRejectionEvaluation() throws Exception {
        Files.createDirectories(output);
        Files.writeString(output.resolve("status.txt"), "RUNNING " + Instant.now());
        var provenance = new LinkedHashMap<String,Object>();
        provenance.put("startedAt", Instant.now().toString());
        provenance.put("embedding", get("http://127.0.0.1:8000/health"));
        provenance.put("reranker", get("http://127.0.0.1:8003/health"));
        provenance.put("qdrant", get("http://127.0.0.1:6333/"));
        assertThat(provenance.get("embedding").toString()).contains("BGE-M3");
        assertThat(provenance.get("reranker").toString()).contains("bge-reranker-v2-m3");
        provenance.put("scope", "real BGE-M3/Qdrant/Lucene/RRF/reranker; oracle standalone queries; extractive enrichment; no answer LLM");
        provenance.put("fixtureManifestSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root().resolve("manifest.json")))));
        write("provenance.json", provenance);
        var props = properties();
        // Cold model load outside production client's short request timeout. Still REAL inference.
        post("http://127.0.0.1:8000/embeddings", "{\"model\":\"bge-m3\",\"input\":[\"模型预热\"]}");
        String warmup = post("http://127.0.0.1:8003/rerank", "{\"query\":\"模型预热\",\"documents\":[{\"id\":\"warmup\",\"text\":\"模型预热\"}],\"topN\":1}");
        assertThat(warmup).contains("bge-reranker-v2-m3");
        var vector = new TraceVector(props);
        vector.ensureCollection();
        var embedding = new EmbeddingClient(props);
        var reranker = new TraceReranker(props);
        // Load only inference-facing DTOs. No evaluator labels have been read yet.
        for (Video v : load().videos()) runVideo(v, props, vector, embedding, reranker);
        assertThat(runs).hasSize(60);
        // Evaluator-only labels enter AFTER all model calls have completed.
        List<Annotation> annotations = JSON.readValue(root().resolve("evaluator/annotations.json").toFile(), new TypeReference<>() {});
        Map<String,Annotation> labels = new LinkedHashMap<>();
        annotations.forEach(a -> labels.put(a.questionId(),a));
        var calibration = runs.stream().filter(r -> labels.get(r.question.id()).split().equals("calibration")).toList();
        double threshold = 0, best = -1;
        List<Object> sweep = new ArrayList<>();
        for (double t : THRESHOLDS) {
            var summary = summarize(calibration, labels, t);
            double balanced = (double) summary.get("balancedAnswerabilityAccuracy");
            sweep.add(Map.of("minScore",t,"calibration",summary));
            // Strictly better: ties keep the lower threshold, to avoid extra false rejections.
            if (balanced>best) { best=balanced; threshold=t; }
        }
        write("calibration-sweep.json",sweep);
        var summaries = new LinkedHashMap<String,Object>();
        summaries.put("selectedMinScore",threshold);
        summaries.put("selection", "maximize calibration balanced answerability accuracy; ties choose lower threshold; frozen before holdout scoring");
        for (String split : List.of("calibration","holdout")) {
            var subset = runs.stream().filter(r -> labels.get(r.question.id()).split().equals(split)).toList();
            summaries.put(split+"Baseline", summarize(subset,labels,0));
            summaries.put(split+"Gated", summarize(subset,labels,threshold));
        }
        summaries.put("answerHallucinationRate", "NOT_MEASURED: no answer-generation LLM; false evidence acceptance is a retrieval proxy only");
        summaries.put("warning", "20 synthetic videos, 60 questions, only 10 negative holdout questions. No production threshold rollout. Hit@1 cannot alone establish reranker value.");
        write("summary.json",summaries);
        var gold = new LinkedHashMap<String,Object>();
        for (Run r : runs) {
            var a = labels.get(r.question.id());
            var g = gains(a,r.chunks);
            gold.put(r.question.id(),g);
            var decision = decide(r.scores,threshold);
            var accepted = r.hits.stream().filter(h -> decision.acceptedChunkIds().contains(h.chunkId())).toList();
            var pack = packs.build(r.video.mediaId(),r.chunks,accepted);
            Map<String,Object> evaluated = new LinkedHashMap<>();
            evaluated.put("annotation",a); evaluated.put("decision",decision);
            evaluated.put("minScore",threshold); evaluated.put("goldChunkGains",g);
            evaluated.put("rawTop5",r.hits); evaluated.put("acceptedTop5",accepted);
            evaluated.put("evidencePack",pack);
            evaluated.put("prompt",decision.promptHint()+"\n"+packs.toPromptText(r.video.title(),pack));
            if (a.related()) {
                evaluated.put("denseAt25",metric(r.dense,g,25));
                evaluated.put("bm25At25",metric(r.bm25,g,25));
                evaluated.put("ocrAt10",metric(r.ocr,g,10));
                evaluated.put("rrfAt10",metric(r.fused,g,10));
                evaluated.put("rrfAt5",metric(r.fused,g,5));
                evaluated.put("rerankAt5",metric(ids(r.hits),g,5));
                evaluated.put("gatedRecallAt5",metric(decision.acceptedChunkIds(),g,5).recallAtK());
            }
            write(r.video.id()+"/"+r.question.id()+"-evaluation.json",evaluated);
            Files.writeString(output.resolve(r.video.id()+"/"+r.question.id()+"-evidencepack.txt"),
                "原始问题: "+r.question.userQuestion()+"\n独立检索输入: "+r.question.standaloneQuery()+
                "\n\n未过滤完整 EvidencePack:\n"+packs.toPromptText(r.video.title(),packs.build(r.video.mediaId(),r.chunks,r.hits))+
                "\n\n阈值评估后的 EvidencePack（实验策略，未上线）:\n"+evaluated.get("prompt"));
        }
        write("groundtruth-chunks.json",gold);
        Files.writeString(output.resolve("summary.txt"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summaries));
        Files.writeString(output.resolve("status.txt"),"COMPLETE "+Instant.now()+"\n60 real retrieval requests; 20 videos; 320 chunks\n");
        System.out.println("Expanded real RAG report: "+output.toAbsolutePath());
    }

    private void runVideo(Video v, AppProperties props, TraceVector vector, EmbeddingClient embedding,
                          TraceReranker reranker) throws Exception {
        var context = context(v);
        var checkpoint = mock(CheckpointService.class); // No production DB/files touched.
        var llm = mock(LlmProvider.class); // null -> REAL extractive enrichment path; no generation.
        try (var bm25 = new TraceBm25()) {
            var index = new RetrievalIndexService(vector,bm25,embedding,new ChunkEnricher(JSON),checkpoint,llm);
            var fusion = new TraceFusion();
            var facade = new VideoEvidenceRetrievalService(index,
                new HybridRetrievalService(vector,bm25,embedding,fusion,reranker),new QueryRewriter(JSON),llm);
            long start = System.nanoTime();
            var chunks = index.index(v.mediaId(),v.contentHash(),context,v.userId());
            long indexMs = (System.nanoTime()-start)/1_000_000;
            assertThat(chunks).hasSize(16).allMatch(c -> c.embedding()!=null && c.embedding().size()==1024);
            var stored = vector.search(chunks.getFirst().embedding(),100,v.userId(),v.contentHash(),RetrievalIndexService.INDEX_VERSION);
            assertThat(stored.stream().map(QdrantVectorStore.Hit::chunkId)).containsExactlyInAnyOrderElementsOf(chunks.stream().map(VideoChunk::chunkId).toList());
            Map<String,VideoChunk> source = new LinkedHashMap<>();
            chunks.forEach(c -> source.put(c.chunkId(),c.withEmbedding(null)));
            write(v.id()+"/indexed-chunks.json", source);
            for (Question question : v.questions()) {
                vector.last=null; reranker.last=null; bm25.content=null; bm25.ocr=null;
                start = System.nanoTime();
                var hits = facade.searchNoRewrite(v.mediaId(),v.contentHash(),context,question.standaloneQuery(),5,v.userId());
                long ms = (System.nanoTime()-start)/1_000_000;
                assertThat(vector.last).as("NO local cosine fallback").isNotNull().isNotEmpty();
                assertThat(reranker.last).as("NO RRF fallback").isNotNull().hasSize(5);
                assertThat(bm25.content).as("BM25 must execute successfully").isNotNull();
                assertThat(bm25.ocr).as("OCR must execute successfully").isNotNull();
                assertThat(hits.stream().map(EvidenceHit::chunkId)).containsExactlyElementsOf(reranker.last.stream().map(RerankerClient.RankedDocument::id).toList());
                assertThat(fusion.fused).hasSize(10);
                var scores = new LinkedHashMap<String,Double>();
                reranker.last.forEach(d -> scores.put(d.id(),d.score()));
                decide(scores,0); // Assert normalized REAL scores, independently of labels.
                for (var h : hits) assertThat(h.score()).isCloseTo(scores.get(h.chunkId()),org.assertj.core.data.Offset.offset(.000001));
                runs.add(new Run(v,question,chunks,hits,scores,
                    fusion.rankings.get(RetrievalChannel.DENSE),fusion.rankings.get(RetrievalChannel.BM25),
                    fusion.rankings.get(RetrievalChannel.OCR),fusion.fused.stream().map(RetrievalCandidate::chunkId).toList(),ms));
                var trace = new LinkedHashMap<String,Object>();
                trace.put("question",question); trace.put("indexMs",indexMs); trace.put("queryMs",ms);
                trace.put("dense",vector.last); trace.put("bm25",bm25.content); trace.put("ocr",bm25.ocr);
                trace.put("rrfTop10",fusion.fused); trace.put("rerankerInputs",reranker.documents);
                trace.put("rerankerTop5",reranker.last); trace.put("finalHits",hits);
                trace.put("rawEvidencePack",packs.build(v.mediaId(),chunks,hits));
                trace.put("chunkContents",source);
                write(v.id()+"/"+question.id()+"-trace.json",trace);
                System.out.println(v.id()+" turn="+question.turn()+" realQueryMs="+ms+" topScore="+reranker.last.getFirst().score());
            }
        }
    }

    private Map<String,Object> summarize(List<Run> subset, Map<String,Annotation> labels, double threshold) {
        int tp=0,fn=0,fp=0,tn=0,hard=0,hardRejected=0,off=0,offRejected=0,acceptedCount=0,badChunks=0;
        List<RagRetrievalMetrics.Report> dense=new ArrayList<>(),rrf10=new ArrayList<>(),rrf5=new ArrayList<>(),rerank=new ArrayList<>(),top1=new ArrayList<>();
        double gatedRecall=0;
        for (Run r : subset) {
            var label=labels.get(r.question.id()); var g=gains(label,r.chunks);
            var selected=decide(r.scores,threshold).acceptedChunkIds(); boolean accepts=!selected.isEmpty();
            acceptedCount+=selected.size(); badChunks+= (int)selected.stream().filter(id -> !g.containsKey(id)).count();
            if (label.related()) {
                if (accepts) tp++; else fn++;
                dense.add(metric(r.dense,g,25)); rrf10.add(metric(r.fused,g,10));
                rrf5.add(metric(r.fused,g,5)); rerank.add(metric(ids(r.hits),g,5)); top1.add(metric(ids(r.hits),g,1));
                gatedRecall+=metric(selected,g,5).recallAtK();
            } else { if (accepts) fp++; else tn++; }
            if (label.category().equals("hard-negative")) { hard++; if(!accepts) hardRejected++; }
            if (label.category().equals("off-topic")) { off++; if(!accepts) offRejected++; }
        }
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("queries",subset.size());out.put("positiveQueries",tp+fn);out.put("negativeQueries",fp+tn);
        out.put("tpAcceptedRelated",tp);out.put("fnRejectedRelated",fn);out.put("fpAcceptedUnrelated",fp);out.put("tnRejectedUnrelated",tn);
        out.put("negativeRejectionRate",ratio(tn,tn+fp));out.put("falseEvidenceAcceptanceRate",ratio(fp,fp+tn));
        out.put("positiveFalseRejectionRate",ratio(fn,tp+fn));out.put("hardNegativeRejectionRate",ratio(hardRejected,hard));
        out.put("offTopicRejectionRate",ratio(offRejected,off));
        out.put("balancedAnswerabilityAccuracy",(ratio(tp,tp+fn)+ratio(tn,tn+fp))/2);
        out.put("acceptedChunkCount",acceptedCount);out.put("unsupportedAcceptedChunkCount",badChunks);
        out.put("unsupportedAcceptedChunkRate",acceptedCount==0 ? "N/A" : ratio(badChunks,acceptedCount));
        out.put("positiveGatedRecallAt5",gatedRecall/(tp+fn));
        out.put("positiveDenseAt25",averages(dense));out.put("positiveRrfAt10",averages(rrf10));
        out.put("positiveRrfAt5",averages(rrf5));out.put("positiveRerankAt5",averages(rerank));
        out.put("positiveRerankHitAt1",mean(top1, r -> r.hitAtK()));
        var timings=subset.stream().mapToLong(Run::queryMs).sorted().toArray();
        out.put("queryLatencyP50Ms",timings[(timings.length-1)/2]);
        out.put("queryLatencyP95Ms",timings[(int)Math.ceil(timings.length*.95)-1]);
        return out;
    }
    private static double ratio(int n,int d) { return d==0?0:(double)n/d; }
    private static double mean(List<RagRetrievalMetrics.Report> values,ToDoubleFunction<RagRetrievalMetrics.Report> f) {return values.stream().mapToDouble(f).average().orElse(0);}
    private static Map<String,Double> averages(List<RagRetrievalMetrics.Report> m) {
        return Map.of("hit",mean(m,r->r.hitAtK()),"precision",mean(m,r->r.precisionAtK()),
            "recall",mean(m,r->r.recallAtK()),"mrr",mean(m,r->r.mrr()),"ndcg",mean(m,r->r.ndcgAtK()));
    }
    private static RagRetrievalMetrics.Report metric(List<String> ids,Map<String,Integer> gains,int k) {return RagRetrievalMetrics.evaluate(ids,gains,k);}
    private static List<String> ids(List<EvidenceHit> hits) {return hits.stream().map(EvidenceHit::chunkId).toList();}
    private void write(String file,Object value) throws Exception {var path=output.resolve(file);Files.createDirectories(path.getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),value);}
    private static String get(String url) throws Exception {return request(HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build());}
    private static String post(String url,String body) throws Exception {return request(HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(180)).build());}
    private static String request(HttpRequest request) throws Exception {
        var r=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            .send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("HTTP response: %s",r.body()).isEqualTo(200);return r.body();
    }
    private static AppProperties properties() {
        return new AppProperties(null,null,null,new AppProperties.Qdrant("http://127.0.0.1:6333"),null,null,
            new AppProperties.Ai(null,new AppProperties.Ai.Embedding("http://127.0.0.1:8000","","bge-m3",1024),null,null,
            new AppProperties.Ai.Reranker("http://127.0.0.1:8003")),null,null,null);
    }
    static class TraceVector extends QdrantVectorStore {
        List<Hit> last;
        TraceVector(AppProperties props){super(props);}
        @Override public List<Hit> search(List<Float> vector,int limit,Long user,String hash,int version) {
            last=super.search(vector,limit,user,hash,version);return last;
        }
    }
    static class TraceReranker extends RerankerClient {
        List<RankedDocument> last; List<DocumentInput> documents;
        TraceReranker(AppProperties props){super(props);}
        @Override public List<RankedDocument> rerank(String query,List<DocumentInput> docs,int topN) {
            documents=List.copyOf(docs);last=super.rerank(query,docs,topN);return last;
        }
    }
    static class TraceFusion extends WeightedRrfFusion {
        Map<RetrievalChannel,List<String>> rankings;List<RetrievalCandidate> fused;
        @Override public List<RetrievalCandidate> fuse(Map<RetrievalChannel,List<String>> input,int limit) {
            rankings=Map.copyOf(input);fused=super.fuse(input,limit);return fused;
        }
    }
    static class TraceBm25 extends Bm25IndexService {
        List<Hit> content,ocr;
        TraceBm25() throws java.io.IOException {super(new ByteBuffersDirectory());}
        @Override public synchronized List<Hit> searchContent(Long user,String hash,int version,String query,int limit) {
            content=super.searchContent(user,hash,version,query,limit);return content;
        }
        @Override public synchronized List<Hit> searchOcr(Long user,String hash,int version,String query,int limit) {
            ocr=super.searchOcr(user,hash,version,query,limit);return ocr;
        }
    }
}
