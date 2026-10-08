package dev.docmind;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeService {
  private final JdbcTemplate db;
  private final AiService ai;
  private final TransactionTemplate tx;
  private final boolean pgvector;

  public KnowledgeService(
      JdbcTemplate db,
      AiService ai,
      PlatformTransactionManager manager,
      @Value("${docmind.pgvector}") boolean pgvector) {
    this.db = db;
    this.ai = ai;
    this.tx = new TransactionTemplate(manager);
    this.pgvector = pgvector;
  }

  public record Chunk(int page, String content) {}

  public record Hit(
      String id, String documentId, String name, int page, String content, double score) {}

  public List<Map<String, Object>> documents(String user) {
    return db.queryForList(
        "SELECT id,name,pages,chunks,size_bytes,created_at FROM documents WHERE user_id=? ORDER BY"
            + " created_at DESC",
        user);
  }

  public Map<String, Object> upload(String user, MultipartFile file) throws Exception {
    String name =
        Optional.ofNullable(file.getOriginalFilename()).orElse("document").replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    if (file.isEmpty() || file.getSize() > 15 * 1024 * 1024 || name.length() > 255)
      bad("Choose a PDF or UTF-8 text file up to 15 MB.");
    if (documents(user).size() >= 100)
      bad("Document limit reached (100 per account). Delete a document first.");
    List<Chunk> chunks = new ArrayList<>();
    int pages = 1;
    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      byte[] bytes = file.getBytes();
      if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
        bad("This file is not a valid PDF.");
      try (var pdf = Loader.loadPDF(bytes)) {
        pages = pdf.getNumberOfPages();
        if (pages > 150) bad("PDFs must contain at most 150 pages.");
        var stripper = new PDFTextStripper();
        for (int p = 1; p <= pages; p++) {
          stripper.setStartPage(p);
          stripper.setEndPage(p);
          chunks.addAll(split(p, stripper.getText(pdf)));
          if (chunks.size() > 500) bad("Document is too large to index (500 chunks maximum).");
        }
      }
    } else if (name.toLowerCase(Locale.ROOT).endsWith(".txt")
        || name.toLowerCase(Locale.ROOT).endsWith(".md"))
      chunks.addAll(split(1, new String(file.getBytes(), StandardCharsets.UTF_8)));
    else bad("Supported formats: PDF, TXT, and Markdown.");
    if (chunks.isEmpty()) bad("No readable text found. Scanned PDFs need OCR before upload.");
    if (chunks.size() > 500) bad("Document is too large to index.");
    var vectors = new ArrayList<String>();
    for (var c : chunks) vectors.add(ai.enabled() ? ai.json(ai.embed(c.content())) : null);
    String id = UUID.randomUUID().toString();
    String finalName = name;
    int finalPages = pages;
    tx.executeWithoutResult(
        status -> {
          db.update(
              "INSERT INTO documents VALUES (?,?,?,?,?,?,?)",
              id,
              user,
              finalName,
              finalPages,
              chunks.size(),
              file.getSize(),
              System.currentTimeMillis());
          for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            String cid = UUID.randomUUID().toString();
            db.update(
                "INSERT INTO chunks VALUES (?,?,?,?,?)",
                cid,
                id,
                c.page(),
                c.content(),
                vectors.get(i));
            if (pgvector && vectors.get(i) != null)
              db.update(
                  "INSERT INTO chunk_vectors VALUES (?,CAST(? AS vector))", cid, vectors.get(i));
          }
        });
    return documents(user).stream().filter(d -> d.get("id").equals(id)).findFirst().orElseThrow();
  }

  public static List<Chunk> split(int page, String raw) {
    String text = raw.replace("\u0000", "").replaceAll("\\s+", " ").trim();
    List<Chunk> out = new ArrayList<>();
    for (int start = 0; start < text.length(); ) {
      int end = Math.min(start + 1200, text.length());
      if (end < text.length()) {
        int space = text.lastIndexOf(' ', end);
        if (space > start + 600) end = space;
      }
      out.add(new Chunk(page, text.substring(start, end)));
      if (end == text.length()) break;
      start = Math.max(start + 1, end - 180);
    }
    return out;
  }

  public List<Hit> search(String user, String question, List<String> ids) {
    var words = tokens(question);
    if (words.isEmpty()) return List.of();
    double[] query = ai.enabled() ? ai.embed(question) : null;
    List<Hit> hits;
    if (pgvector && query != null) {
      String filter =
          ids.isEmpty()
              ? ""
              : " AND d.id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
      List<Object> args = new ArrayList<>();
      args.add(ai.json(query));
      args.add(user);
      args.addAll(ids);
      args.add(ai.json(query));
      hits =
          db.query(
              "SELECT c.id,d.id,d.name,c.page,c.content,1-(v.embedding <=> CAST(? AS vector)) score"
                  + " FROM chunk_vectors v JOIN chunks c ON c.id=v.chunk_id JOIN documents d ON"
                  + " d.id=c.document_id WHERE d.user_id=?"
                  + filter
                  + " ORDER BY v.embedding <=> CAST(? AS vector) LIMIT 6",
              (r, n) ->
                  new Hit(
                      r.getString(1),
                      r.getString(2),
                      r.getString(3),
                      r.getInt(4),
                      r.getString(5),
                      r.getDouble(6)),
              args.toArray());
    } else
      hits =
          db
              .query(
                  "SELECT c.id,d.id,d.name,c.page,c.content,c.embedding FROM chunks c JOIN"
                      + " documents d ON d.id=c.document_id WHERE d.user_id=?",
                  (r, n) -> {
                    String content = r.getString(5), embedding = r.getString(6);
                    double score =
                        query != null && embedding != null
                            ? cosine(query, ai.vector(embedding))
                            : lexical(words, content);
                    return new Hit(
                        r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getInt(4),
                        content,
                        score);
                  },
                  user)
              .stream()
              .filter(h -> ids.isEmpty() || ids.contains(h.documentId()))
              .sorted(Comparator.comparingDouble(Hit::score).reversed())
              .limit(6)
              .toList();
    return hits.stream().filter(h -> h.score() > (query != null ? 0.15 : 0)).toList();
  }

  static Set<String> tokens(String s) {
    Set<String> result = new HashSet<>();
    Set<String> stop =
        Set.of(
            "the",
            "and",
            "what",
            "does",
            "this",
            "that",
            "with",
            "from",
            "have",
            "about",
            "are",
            "for",
            "how",
            "can",
            "tell",
            "please",
            "document");
    for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
      if (w.length() > 2 && !stop.contains(w)) result.add(w);
    return result;
  }

  static double lexical(Set<String> words, String content) {
    Set<String> terms = tokens(content);
    return words.stream().filter(terms::contains).count() / (double) Math.max(1, words.size());
  }

  static double cosine(double[] a, double[] b) {
    if (a.length != b.length) return 0;
    double dot = 0, x = 0, y = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      x += a[i] * a[i];
      y += b[i] * b[i];
    }
    return x * y == 0 ? 0 : dot / Math.sqrt(x * y);
  }

  public void delete(String user, String id) {
    if (db.update("DELETE FROM documents WHERE id=? AND user_id=?", id, user) == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
  }

  private static void bad(String s) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, s);
  }
}
