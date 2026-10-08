package dev.docmind;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public AuthService(JdbcTemplate db) {
    this.db = db;
  }

  public record User(String id, String email, String name) {}

  public record Login(String token, User user) {}

  public Login register(String name, String email, String password) {
    email = email.trim().toLowerCase(Locale.ROOT);
    name = name.trim();
    if (name.isBlank()
        || name.length() > 80
        || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        || email.length() > 254
        || password.length() < 10
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Use a valid email, name, and a password of 10–72 bytes.");
    User u = new User(UUID.randomUUID().toString(), email, name);
    try {
      db.update(
          "INSERT INTO users VALUES (?,?,?,?)", u.id(), email, name, encoder.encode(password));
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already registered.");
    }
    return issue(u);
  }

  public Login login(String email, String password) {
    var rows =
        db.queryForList("SELECT * FROM users WHERE email=?", email.trim().toLowerCase(Locale.ROOT));
    if (rows.isEmpty()
        || password.getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(password, (String) rows.get(0).get("password")))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
    var r = rows.get(0);
    return issue(new User((String) r.get("id"), (String) r.get("email"), (String) r.get("name")));
  }

  private Login issue(User user) {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    db.update("DELETE FROM sessions WHERE expires_at<?", System.currentTimeMillis());
    db.update(
        "INSERT INTO sessions VALUES (?,?,?)",
        hash(t),
        user.id(),
        System.currentTimeMillis() + 86400000L);
    return new Login(t, user);
  }

  public User require(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer "))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
    var rows =
        db.query(
            "SELECT u.id,u.email,u.name FROM users u JOIN sessions s ON s.user_id=u.id WHERE"
                + " s.token=? AND s.expires_at>?",
            (r, n) -> new User(r.getString(1), r.getString(2), r.getString(3)),
            hash(authorization.substring(7)),
            System.currentTimeMillis());
    if (rows.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Your session expired. Please sign in.");
    return rows.get(0);
  }

  public void logout(String auth) {
    if (auth != null && auth.startsWith("Bearer "))
      db.update("DELETE FROM sessions WHERE token=?", hash(auth.substring(7)));
  }

  private static String hash(String v) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
