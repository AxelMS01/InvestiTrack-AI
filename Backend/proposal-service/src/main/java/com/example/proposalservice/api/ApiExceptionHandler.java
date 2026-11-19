package com.example.proposalservice.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> domain(ApiException e, HttpServletRequest r) { return problem(e.status(),e.code(),e.getMessage(),r); }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    ResponseEntity<ProblemDetail> stale(Exception e, HttpServletRequest r) { return problem(412,"STALE_VERSION","The resource changed. Fetch the current ETag.",r); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(Exception e, HttpServletRequest r) { return problem(409,"RESOURCE_CONFLICT","The operation conflicts with an existing record.",r); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
        var response=problem(422,"VALIDATION_FAILED","Request violates field validation.",r);
        response.getBody().setProperty("errors",e.getBindingResult().getFieldErrors().stream()
            .map(f -> Map.of("field",f.getField(),"message",f.getDefaultMessage()==null?"Invalid value":f.getDefaultMessage())).toList());
        return response;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class, IllegalArgumentException.class, ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> malformed(Exception e, HttpServletRequest r) { return problem(400,"INVALID_REQUEST","Malformed request or invalid parameter.",r); }
    private ResponseEntity<ProblemDetail> problem(int status,String code,String detail,HttpServletRequest r) {
        var p=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status),detail);
        p.setTitle(code.replace('_',' ')); p.setType(URI.create("urn:proposal-service:problem:"+code.toLowerCase().replace('_','-')));
        p.setInstance(URI.create(r.getRequestURI())); p.setProperty("code",code);
        String correlation=r.getHeader("X-Correlation-ID");
        p.setProperty("correlationId",correlation==null?UUID.randomUUID().toString():correlation);
        return ResponseEntity.status(status).body(p);
    }
}
