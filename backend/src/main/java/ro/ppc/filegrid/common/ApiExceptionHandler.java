package ro.ppc.filegrid.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 problem document. Framework errors
 * (validation, missing parts, upload too large, ...) are handled by the base class.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final TraceIds traceIds;

    public ApiExceptionHandler(TraceIds traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        if (e.status().is5xxServerError()) {
            log.error("{} ({})", e.getMessage(), e.code(), e);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setProperty("code", e.code());
        String traceId = traceIds.current();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return ResponseEntity.status(e.status()).body(problem);
    }
}
