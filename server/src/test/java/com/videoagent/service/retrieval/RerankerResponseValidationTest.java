package com.videoagent.service.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Protocol regression checks; does not change retrieval fixtures or golden questions. */
class RerankerResponseValidationTest {
    @Test void rejectsInvalidScoresUnknownIdsDuplicatesAndMissingResults() {
        for (String results : List.of(
                "[{\"id\":\"a\",\"score\":1.1},{\"id\":\"b\",\"score\":0.2}]",
                "[{\"id\":\"a\",\"score\":\"0.8\"},{\"id\":\"b\",\"score\":0.2}]",
                "[{\"id\":\"other\",\"score\":0.8},{\"id\":\"b\",\"score\":0.2}]",
                "[{\"id\":\"a\",\"score\":0.8},{\"id\":\"a\",\"score\":0.2}]",
                "[{\"id\":\"a\",\"score\":0.8}]")) {
            var builder = RestClient.builder().baseUrl("http://reranker.test");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://reranker.test/rerank"))
                    .andRespond(withSuccess("{\"results\":" + results + "}", MediaType.APPLICATION_JSON));
            var client = new RerankerClient(builder.build());
            assertThatThrownBy(() -> client.rerank("q", List.of(
                    new RerankerClient.DocumentInput("a", "A"),
                    new RerankerClient.DocumentInput("b", "B")), 2))
                    .isInstanceOf(IllegalStateException.class);
            server.verify();
        }
    }
}
