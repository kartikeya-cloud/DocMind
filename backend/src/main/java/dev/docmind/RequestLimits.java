package dev.docmind;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLimits extends OncePerRequestFilter {
  private record Window(long start, int count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "same-origin");
    res.setHeader("X-Frame-Options", "DENY");
    if (req.getRequestURI().startsWith("/api")) res.setHeader("Cache-Control", "no-store");
    if ("POST".equals(req.getMethod())) {
      long now = System.currentTimeMillis();
      String group = req.getRequestURI().startsWith("/api/auth") ? "auth" : "work";
      String identity = req.getHeader("Authorization");
      String key = (identity == null ? req.getRemoteAddr() : identity) + group;
      int max = group.equals("auth") ? 15 : 40;
      windows.entrySet().removeIf(e -> now - e.getValue().start() > 60000);
      Window w =
          windows.compute(
              key,
              (k, old) ->
                  old == null || now - old.start() > 60000
                      ? new Window(now, 1)
                      : new Window(old.start(), old.count() + 1));
      if (w.count() > max) {
        res.setStatus(429);
        res.setHeader("Retry-After", "60");
        res.setContentType("application/json");
        res.getWriter().write("{\"message\":\"Too many requests. Try again in a minute.\"}");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
