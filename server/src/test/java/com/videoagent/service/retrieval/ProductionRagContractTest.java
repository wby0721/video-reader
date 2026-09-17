package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.config.AppProperties;
import com.videoagent.dto.*;
import com.videoagent.entity.MediaFile;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.utils.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductionRagContractTest {
    @Test void globalQuotaUsesCandidatesBeyondFirstTenWithoutCallingAnAnswerModel() {
        var repo=mock(MediaFileRepository.class);
        var cp=mock(CheckpointService.class);
        var index=mock(RetrievalIndexService.class);
        var hybrid=mock(HybridRetrievalService.class);
        var media=new ArrayList<MediaFile>(); var candidates=new ArrayList<RetrievalCandidate>();
        var context=VideoContext.of("scope","",List.of());
        for (long id=1;id<=4;id++) {
            var m=new MediaFile();m.setId(id);m.setUserId(7L);m.setContentHash("h"+id);
            m.setFilename("video"+id);m.setStatus(MediaFile.STATUS_CONTEXT_READY);media.add(m);
            when(cp.loadVideoContext(id)).thenReturn(Optional.of(context));
            var chunks=new ArrayList<VideoChunk>();
            for (int j=0;j<(id==1?12:3);j++) {
                String cid=id+"-"+j;
                chunks.add(VideoChunk.indexed(0,90000,"original",List.of(),List.of(),cid,j,"h"+id,2));
                candidates.add(new RetrievalCandidate(cid,.01,1,null,null,Set.of(RetrievalChannel.DENSE),.8));
            }
            when(index.indexForSearch(id,"h"+id,context,7L)).thenReturn(chunks);
        }
        when(repo.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(media);
        when(hybrid.retrieve(any(),any(),any(),eq(60))).thenReturn(new RetrievalResult(candidates,"QDRANT"));
        var response=new GlobalKnowledgeSearchService(repo,cp,index,hybrid).searchDetailed(7L,"query",10);
        assertThat(response.hits()).hasSize(10);
        assertThat(response.hits().stream().filter(h->h.mediaId()==1L)).hasSize(3);
        assertThat(response.hits().getLast().mediaId()).isEqualTo(4L);
        verify(index,never()).index(any(),any(),any(),any());
    }

    @Test void coldGlobalIndexDoesNotEvenRequestAnLlmClient() {
        var llm=mock(LlmProvider.class);var embedding=mock(EmbeddingClient.class);
        when(embedding.embedAll(any())).thenReturn(List.of(List.of(1f,0f)));
        var index=new RetrievalIndexService(mock(QdrantVectorStore.class),mock(Bm25IndexService.class),
                embedding,new ChunkEnricher(new ObjectMapper()),mock(CheckpointService.class),llm);
        var context=VideoContext.of("1","",List.of(VideoSegment.of(0,60000,"原文",List.of("PPT"),List.of())));
        assertThat(index.indexForSearch(1L,"hash",context,7L)).hasSize(1);
        verifyNoInteractions(llm);
    }

    @Test void chatPreparationPreservesWarningAndDoesNotMislabelOutage() {
        var strict=new ChatEvidenceService(new RelevancePolicy(new AppProperties.Retrieval(null,.5,true)));
        var hit=new EvidenceHit(0,1000,"a","summary",List.of(),.01,List.of(),"QDRANT",EvidenceHit.ScoreType.RERANKER_SIGMOID);
        var chunk=VideoChunk.indexed(0,1000,"original",List.of(),List.of(),"a",0,"h",2);
        var prepared=strict.prepare(1L,new VideoEvidenceRetrievalService.PreparedSearch(List.of(hit),List.of(chunk),"QDRANT"));
        assertThat(prepared.evidence()).isEmpty();
        assertThat(prepared.retrieval().status()).isEqualTo(RetrievalAssessment.Status.LOW_RELEVANCE);
        var outage=strict.prepare(1L,new VideoEvidenceRetrievalService.PreparedSearch(List.of(),List.of(chunk),"EMBEDDING_UNAVAILABLE"));
        assertThat(outage.retrieval().status()).isEqualTo(RetrievalAssessment.Status.DEGRADED);
    }

    @Test void bindsDeploymentSettingsAndReadsHistoricalChatJson() throws Exception {
        var properties=new Binder(new MapConfigurationPropertySource(Map.of(
                "app.retrieval.min-reranker-score","0.75","app.retrieval.reject-low-relevance","true",
                "app.ai.reranker.base-url","http://localhost:8003","app.ai.reranker.read-timeout-ms","45000")))
                .bind("app", Bindable.of(AppProperties.class)).get();
        assertThat(properties.retrieval().minRerankerScore()).isEqualTo(.75);
        assertThat(properties.retrieval().rejectLowRelevance()).isTrue();
        assertThat(properties.ai().reranker().readTimeoutMs()).isEqualTo(45000);
        var old=new ObjectMapper().readValue("{\"role\":\"assistant\",\"content\":\"old\",\"ts\":1,\"evidence\":[]}",ChatEntry.class);
        assertThat(old.retrieval()).isNull();
    }
}
