package com.example.documentservice.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
import java.net.URI;
import java.util.Locale;

@RestControllerAdvice
public class Problems {
    @ExceptionHandler(ApiException.class) ResponseEntity<ProblemDetail> domain(ApiException e,HttpServletRequest r) {
        return response(e.status,e.code,e.getMessage(),r);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,MissingRequestValueException.class,IllegalArgumentException.class,java.time.DateTimeException.class,tools.jackson.core.JacksonException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e,HttpServletRequest r) {
        return response(400,"INVALID_REQUEST","The request contains invalid or missing fields.",r);
    }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<ProblemDetail> conflict(Exception e,HttpServletRequest r) {
        return response(409,"RESOURCE_CONFLICT","A uniqueness or protected-reference constraint prevents this operation.",r);
    }
    @ExceptionHandler({CannotAcquireLockException.class,OptimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> concurrent(Exception e,HttpServletRequest r) {
        return response(409,"CONCURRENT_MODIFICATION","The resource changed concurrently. Refresh and retry.",r);
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ProblemDetail> database(Exception e,HttpServletRequest r) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Document persistence failure; correlationId={}",r.getAttribute("correlationId"),e);
        return response(503,"DOCUMENT_STORAGE_UNAVAILABLE","Document storage is temporarily unavailable.",r);
    }
    public static ResponseEntity<ProblemDetail> response(int status,String code,String detail,HttpServletRequest r) {
        var problem=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),detail);
        problem.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        problem.setType(URI.create("urn:document-service:problem:"+code.toLowerCase(Locale.ROOT).replace('_','-')));
        problem.setInstance(URI.create(r.getRequestURI()));
        problem.setProperty("code",code); problem.setProperty("correlationId",r.getAttribute("correlationId"));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
