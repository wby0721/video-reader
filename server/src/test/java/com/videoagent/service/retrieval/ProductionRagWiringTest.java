package com.videoagent.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.config.AppProperties;
import com.videoagent.repository.MediaFileRepository;
import com.videoagent.service.CheckpointService;
import com.videoagent.service.agent.EvidencePackService;
import com.videoagent.service.ai.LlmProvider;
import com.videoagent.utils.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductionRagWiringTest {
    @TempDir Path directory;

    @Test void springConstructsAllThreeProductionRetrievalConsumers() {
        var props = new AppProperties(null,null,null,new AppProperties.Qdrant("http://localhost:6333"),
                null,new AppProperties.Retrieval(directory.toString(),.5,false),
                new AppProperties.Ai(null,new AppProperties.Ai.Embedding("http://localhost:8000","","bge-m3",1024),
                        null,null,new AppProperties.Ai.Reranker("http://localhost:8003")),null,null,null);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppProperties.class, () -> props);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(CheckpointService.class, () -> mock(CheckpointService.class));
            context.registerBean(MediaFileRepository.class, () -> mock(MediaFileRepository.class));
            context.registerBean(LlmProvider.class, () -> mock(LlmProvider.class));
            context.register(EmbeddingClient.class,QdrantVectorStore.class,Bm25IndexService.class,
                    RerankerClient.class,WeightedRrfFusion.class,ChunkEnricher.class,QueryRewriter.class,
                    RetrievalIndexService.class,HybridRetrievalService.class,VideoEvidenceRetrievalService.class,
                    RelevancePolicy.class,ChatEvidenceService.class,EvidencePackService.class,GlobalKnowledgeSearchService.class);
            context.refresh();
            assertThat(context.getBean(ChatEvidenceService.class)).isNotNull();
            assertThat(context.getBean(EvidencePackService.class)).isNotNull();
            assertThat(context.getBean(GlobalKnowledgeSearchService.class)).isNotNull();
        }
    }
}
