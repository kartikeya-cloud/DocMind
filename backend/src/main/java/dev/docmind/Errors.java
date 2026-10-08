package dev.docmind;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("message", e.getReason() == null ? "Request failed." : e.getReason()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<?> validation(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Check your request fields and try again."));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> size(Exception e) {
    return ResponseEntity.status(413)
        .body(Map.of("message", "File exceeds the 15 MB upload limit."));
  }

  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  public ResponseEntity<?> ai(Exception e) {
    LoggerFactory.getLogger(Errors.class)
        .warn("AI provider request failed: {}", e.getClass().getSimpleName());
    return ResponseEntity.status(502)
        .body(
            Map.of(
                "message",
                "The AI service is unavailable. Check server configuration or try again shortly."));
  }

  @ExceptionHandler(java.io.IOException.class)
  public ResponseEntity<?> pdf(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "message",
                "Could not read this file. Use an unencrypted text PDF or UTF-8 text file."));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> other(Exception e) {
    LoggerFactory.getLogger(Errors.class).error("Request failed", e);
    return ResponseEntity.internalServerError()
        .body(Map.of("message", "Something went wrong. Please try again."));
  }
}
