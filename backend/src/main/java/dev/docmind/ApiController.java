package dev.docmind;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final AuthService auth;
  private final KnowledgeService knowledge;
  private final AiService ai;
  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;

  public ApiController(
      AuthService auth,
      KnowledgeService knowledge,
      AiService ai,
      JdbcTemplate db,
      ObjectMapper mapper,
      PlatformTransactionManager manager) {
    this.auth = auth;
    this.knowledge = knowledge;
    this.ai = ai;
    this.db = db;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(manager);
  }

  public record Credentials(
      @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

  public record Registration(
      @NotBlank @Size(max = 80) String name,
      @NotBlank @Size(max = 254) String email,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  public record Question(
      @NotBlank @Size(max = 2000) String question,
      @Size(max = 36) String conversationId,
      @NotNull @Size(max = 100) List<@NotBlank @Size(max = 36) String> documentIds) {}

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("status", "ok", "mode", ai.enabled() ? "ai" : "local");
  }

  @PostMapping("/auth/register")
  public AuthService.Login register(@Valid @RequestBody Registration r) {
    return auth.register(r.name(), r.email(), r.password());
  }

  @PostMapping("/auth/login")
  public AuthService.Login login(@Valid @RequestBody Credentials r) {
    return auth.login(r.email(), r.password());
  }

  @GetMapping("/auth/me")
  public AuthService.User me(@RequestHeader(value = "Authorization", required = false) String a) {
    return auth.require(a);
  }

  @PostMapping("/auth/logout")
  public void logout(@RequestHeader(value = "Authorization", required = false) String a) {
    auth.logout(a);
  }

  @GetMapping("/documents")
  public Object documents(@RequestHeader(value = "Authorization", required = false) String a) {
    return knowledge.documents(auth.require(a).id());
  }

  @PostMapping("/documents")
  public Object upload(
      @RequestHeader(value = "Authorization", required = false) String a,
      @RequestParam("file") MultipartFile f)
      throws Exception {
    return knowledge.upload(auth.require(a).id(), f);
  }

  @DeleteMapping("/documents/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", required = false) String a, @PathVariable String id) {
    knowledge.delete(auth.require(a).id(), id);
  }

  @GetMapping("/search")
  public Object search(
      @RequestHeader(value = "Authorization", required = false) String a, @RequestParam String q) {
    if (q.isBlank() || q.length() > 2000)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Enter a search of 1–2000 characters.");
    return knowledge.search(auth.require(a).id(), q, List.of());
  }

  @GetMapping("/conversations")
  public Object conversations(@RequestHeader(value = "Authorization", required = false) String a) {
    return db.queryForList(
        "SELECT id,title,created_at FROM conversations WHERE user_id=? ORDER BY created_at DESC",
        auth.require(a).id());
  }

  private void own(String user, String id) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM conversations WHERE id=? AND user_id=?", Integer.class, id, user)
        == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
  }

  private List<Map<String, Object>> messages(String id) {
    var rows =
        db.queryForList(
            "SELECT id,role,content,citations,mode,created_at FROM messages WHERE conversation_id=?"
                + " ORDER BY created_at,id",
            id);
    rows.forEach(
        r -> {
          try {
            r.put("citations", mapper.readValue(r.get("citations").toString(), List.class));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    return rows;
  }

  @GetMapping("/conversations/{id}")
  public Object history(
      @RequestHeader(value = "Authorization", required = false) String a, @PathVariable String id) {
    own(auth.require(a).id(), id);
    return messages(id);
  }

  @DeleteMapping("/conversations/{id}")
  public void deleteChat(
      @RequestHeader(value = "Authorization", required = false) String a, @PathVariable String id) {
    String user = auth.require(a).id();
    own(user, id);
    db.update("DELETE FROM conversations WHERE id=? AND user_id=?", id, user);
  }

  @PostMapping("/chat")
  public Object chat(
      @RequestHeader(value = "Authorization", required = false) String a,
      @Valid @RequestBody Question r) {
    String user = auth.require(a).id();
    String id = r.conversationId();
    boolean fresh = id == null || id.isBlank();
    if (!fresh) own(user, id);
    var hits = knowledge.search(user, r.question(), r.documentIds());
    String answer;
    if (hits.isEmpty())
      answer =
          "I could not find supporting evidence in the selected documents. Try a more specific"
              + " question or upload a relevant document.";
    else if (ai.enabled()) answer = ai.answer(r.question(), hits, fresh ? List.of() : messages(id));
    else {
      StringBuilder b =
          new StringBuilder("Here are the matching passages from your documents:\n\n");
      for (int i = 0; i < Math.min(3, hits.size()); i++)
        b.append("[" + (i + 1) + "] " + hits.get(i).content() + "\n\n");
      b.append("Local mode retrieves excerpts. Enable AI mode for synthesized answers.");
      answer = b.toString();
    }
    var citations = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < hits.size(); i++) {
      var h = hits.get(i);
      if (answer.contains("[" + (i + 1) + "]"))
        citations.add(
            Map.of(
                "index",
                i + 1,
                "documentId",
                h.documentId(),
                "name",
                h.name(),
                "page",
                h.page(),
                "excerpt",
                h.content()));
    }
    String cid = fresh ? UUID.randomUUID().toString() : id;
    String finalAnswer = answer;
    long now = System.currentTimeMillis();
    String mode = ai.enabled() ? "ai" : "local";
    tx.executeWithoutResult(
        s -> {
          if (fresh)
            db.update(
                "INSERT INTO conversations VALUES (?,?,?,?)",
                cid,
                user,
                r.question().substring(0, Math.min(100, r.question().length())),
                now);
          db.update(
              "INSERT INTO messages VALUES (?,?,?,?,?,?,?)",
              UUID.randomUUID().toString(),
              cid,
              "user",
              r.question(),
              "[]",
              mode,
              now);
          db.update(
              "INSERT INTO messages VALUES (?,?,?,?,?,?,?)",
              UUID.randomUUID().toString(),
              cid,
              "assistant",
              finalAnswer,
              ai.json(citations),
              mode,
              now + 1);
        });
    return Map.of("conversationId", cid, "messages", messages(cid));
  }
}
