package com.example.identityservice.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import java.net.URI;

@RestControllerAdvice
public class Problems {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest r) {
        return problem(e.status, e.code, e.getMessage(), r);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
        MissingRequestValueException.class, IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e, HttpServletRequest r) {
        return problem(400, "INVALID_REQUEST", "The request contains invalid or missing fields.", r);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> duplicate(Exception e, HttpServletRequest r) {
        return problem(409, "IDENTITY_CONFLICT", "An identity, email, or institutional ID is already registered.", r);
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(Exception e, HttpServletRequest r) {
        return problem(503, "IDENTITY_UNAVAILABLE", "Identity storage is temporarily unavailable.", r);
    }
    static ResponseEntity<ProblemDetail> problem(int status, String code, String detail, HttpServletRequest r) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        p.setType(URI.create("urn:identity-service:problem:" + code.toLowerCase().replace('_','-')));
        p.setTitle(code); p.setInstance(URI.create(r.getRequestURI()));
        p.setProperty("code", code); p.setProperty("correlationId", r.getAttribute("correlationId"));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
