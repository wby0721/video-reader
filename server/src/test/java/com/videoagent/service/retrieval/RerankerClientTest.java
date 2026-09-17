package com.videoagent.service.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RerankerClientTest {

    @Test
    void rerankSendsOneBatchAndReadsRankedResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reranker.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RerankerClient client = new RerankerClient(builder.build());
        server.expect(requestTo("http://reranker.test/rerank"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "query":"TCP 为什么握手",
                          "documents":[
                            {"id":"a","text":"doc a"},
                            {"id":"b","text":"doc b"}
                          ],
                          "topN":2
                        }
                        """))
                .andRespond(withSuccess("""
                        {"results":[{"id":"b","score":0.91},{"id":"a","score":0.42}]}
                        """, MediaType.APPLICATION_JSON));

        List<RerankerClient.RankedDocument> result = client.rerank("TCP 为什么握手", List.of(
                new RerankerClient.DocumentInput("a", "doc a"),
                new RerankerClient.DocumentInput("b", "doc b")), 5);

        assertThat(result).containsExactly(
                new RerankerClient.RankedDocument("b", .91),
                new RerankerClient.RankedDocument("a", .42));
        server.verify();
    }

    @Test
    void malformedResponseFailsSoCallerCanFallBackToRrf() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reranker.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RerankerClient client = new RerankerClient(builder.build());
        server.expect(requestTo("http://reranker.test/rerank"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.rerank("q", List.of(
                new RerankerClient.DocumentInput("a", "doc")), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("results");
    }

    @Test
    void rerankBatchKeepsResultsSeparatedByQuery() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://reranker.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RerankerClient client = new RerankerClient(builder.build());
        server.expect(requestTo("http://reranker.test/rerank/batch"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("""
                        {"requests":[
                          {"query":"q1","documents":[{"id":"a","text":"A"}],"topN":1},
                          {"query":"q2","documents":[{"id":"b","text":"B"}],"topN":1}
                        ]}
                        """))
                .andRespond(withSuccess("""
                        {"results":[
                          {"results":[{"id":"a","score":0.8}]},
                          {"results":[{"id":"b","score":0.9}]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<List<RerankerClient.RankedDocument>> results = client.rerankBatch(List.of(
                new RerankerClient.BatchInput("q1", List.of(
                        new RerankerClient.DocumentInput("a", "A")), 1),
                new RerankerClient.BatchInput("q2", List.of(
                        new RerankerClient.DocumentInput("b", "B")), 1)));

        assertThat(results).containsExactly(
                List.of(new RerankerClient.RankedDocument("a", .8)),
                List.of(new RerankerClient.RankedDocument("b", .9)));
        server.verify();
    }
}
