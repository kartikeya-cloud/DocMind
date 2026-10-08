package dev.docmind;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:ai-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "docmind.ai-key=test-key",
      "docmind.pgvector=false"
    })
@AutoConfigureMockMvc
class AiIntegrationTest {
  static final AtomicBoolean fail = new AtomicBoolean(false);
  static final ObjectMapper json = new ObjectMapper();
  static final HttpServer provider;

  static {
    try {
      provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext(
          "/v1/embeddings",
          exchange -> {
            int status = fail.get() ? 500 : 200;
            double[] embedding = new double[1536];
            embedding[0] = 1;
            byte[] body =
                json.writeValueAsBytes(
                    status == 200
                        ? Map.of("data", java.util.List.of(Map.of("embedding", embedding)))
                        : Map.of("error", "unavailable"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      provider.createContext(
          "/v1/chat/completions",
          exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            String prompt =
                request
                    .get("messages")
                    .get(request.get("messages").size() - 1)
                    .get("content")
                    .asText();
            String answer =
                prompt.contains("November")
                    ? "The release launches in November [1]."
                    : "Insufficient evidence.";
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "choices",
                        java.util.List.of(Map.of("message", Map.of("content", answer)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      provider.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry r) {
    r.add("docmind.ai-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @AfterAll
  static void stop() {
    provider.stop(0);
  }

  @Test
  void semanticPipelineAndProviderFailureAreHandled() throws Exception {
    String token =
        mapper
            .readTree(
                mvc.perform(
                        post("/api/auth/register")
                            .contentType("application/json")
                            .content(
                                "{\"name\":\"AI"
                                    + " tester\",\"email\":\"ai@example.com\",\"password\":\"secure-test-123\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("token")
            .asText();
    String auth = "Bearer " + token;
    mvc.perform(
            multipart("/api/documents")
                .file(
                    new MockMultipartFile(
                        "file",
                        "release.txt",
                        "text/plain",
                        "The release launches in November.".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", auth))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/chat")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"question\":\"When is the release?\",\"documentIds\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages[1].mode").value("ai"))
        .andExpect(jsonPath("$.messages[1].citations[0].page").value(1))
        .andExpect(
            jsonPath("$.messages[1].content").value("The release launches in November [1]."));
    fail.set(true);
    try {
      mvc.perform(
              multipart("/api/documents")
                  .file(
                      new MockMultipartFile(
                          "file",
                          "failure.txt",
                          "text/plain",
                          "Provider failure must not save partial content."
                              .getBytes(StandardCharsets.UTF_8)))
                  .header("Authorization", auth))
          .andExpect(status().isBadGateway());
    } finally {
      fail.set(false);
    }
    var docs =
        mapper.readTree(
            mvc.perform(get("/api/documents").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals(1, docs.size());
  }
}
