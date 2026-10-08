package dev.docmind;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
      "docmind.ai-key=",
      "docmind.pgvector=false"
    })
@AutoConfigureMockMvc
class DocmindIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  String register(String email) throws Exception {
    return mapper
        .readTree(
            mvc.perform(
                    post("/api/auth/register")
                        .contentType("application/json")
                        .content(
                            mapper.writeValueAsString(
                                java.util.Map.of(
                                    "name",
                                    "Tester",
                                    "email",
                                    email,
                                    "password",
                                    "secure-test-123"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("token")
        .asText();
  }

  @Test
  void documentsArePrivateAndConversationsPersist() throws Exception {
    String a = register("alice@example.com"), b = register("bob@example.com");
    String bearer = "Bearer " + a;
    mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
    var f =
        new MockMultipartFile(
            "file",
            "roadmap.txt",
            "text/plain",
            "The project roadmap launches in November. The release budget is 5000 credits."
                .getBytes(StandardCharsets.UTF_8));
    JsonNode d =
        mapper.readTree(
            mvc.perform(multipart("/api/documents").file(f).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    mvc.perform(get("/api/documents").header("Authorization", "Bearer " + b))
        .andExpect(content().json("[]"));
    mvc.perform(
            delete("/api/documents/" + d.get("id").asText()).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/search").param("q", "roadmap").header("Authorization", "Bearer " + b))
        .andExpect(content().json("[]"));
    JsonNode chat =
        mapper.readTree(
            mvc.perform(
                    post("/api/chat")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content(
                            "{\"question\":\"When does the roadmap launch?\",\"documentIds\":[]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertEquals(2, chat.get("messages").size());
    assertTrue(chat.get("messages").get(1).get("content").asText().contains("November"));
    assertEquals(1, chat.get("messages").get(1).get("citations").size());
    String cid = chat.get("conversationId").asText();
    mvc.perform(get("/api/conversations/" + cid).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/conversations/" + cid).header("Authorization", bearer))
        .andExpect(jsonPath("$.length()").value(2));
    mvc.perform(
            post("/api/chat")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("{\"question\":\"quantum bananas\",\"documentIds\":[]}"))
        .andExpect(jsonPath("$.messages[1].citations.length()").value(0));
    mvc.perform(delete("/api/documents/" + d.get("id").asText()).header("Authorization", bearer))
        .andExpect(status().isOk());
    mvc.perform(get("/api/search").param("q", "roadmap").header("Authorization", bearer))
        .andExpect(content().json("[]"));
    mvc.perform(post("/api/auth/logout").header("Authorization", bearer))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pdfCitationsPreservePageNumbers() throws Exception {
    String token = register("pdf@example.com");
    byte[] bytes;
    try (var pdf = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      for (String text :
          new String[] {
            "First page introduces the company.", "Second page: zephyr launch deadline is December."
          }) {
        var page = new PDPage();
        pdf.addPage(page);
        try (var stream = new PDPageContentStream(pdf, page)) {
          stream.beginText();
          stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          stream.newLineAtOffset(60, 700);
          stream.showText(text);
          stream.endText();
        }
      }
      pdf.save(out);
      bytes = out.toByteArray();
    }
    mvc.perform(
            multipart("/api/documents")
                .file(new MockMultipartFile("file", "launch.pdf", "application/pdf", bytes))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pages").value(2));
    mvc.perform(get("/api/search").param("q", "zephyr").header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$[0].page").value(2));
    mvc.perform(
            multipart("/api/documents")
                .file(
                    new MockMultipartFile("file", "fake.pdf", "application/pdf", "nope".getBytes()))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void chunkOverlapAndCosine() {
    String s = "knowledge ".repeat(400);
    var chunks = KnowledgeService.split(3, s);
    assertTrue(chunks.size() > 1);
    assertEquals(3, chunks.get(0).page());
    assertTrue(chunks.get(0).content().endsWith(chunks.get(1).content().substring(0, 180)));
    assertEquals(1, KnowledgeService.cosine(new double[] {1, 0}, new double[] {1, 0}), .001);
    assertEquals(0, KnowledgeService.cosine(new double[] {0, 0}, new double[] {1, 0}), .001);
  }

  @Test
  void invalidCredentialsAndDuplicateEmails() throws Exception {
    register("unique@example.com");
    mvc.perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"unique@example.com\",\"password\":\"incorrect\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/register")
                .contentType("application/json")
                .content(
                    "{\"name\":\"Test\",\"email\":\"unique@example.com\",\"password\":\"secure-test-123\"}"))
        .andExpect(status().isConflict());
  }
}
