package chatbot.chat.rag.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiErrorHandler.class);
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
        return problem(e.getStatusCode(), e.getReason()==null ? "请求失败" : e.getReason());
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missing(NoSuchElementException e) { return problem(HttpStatus.NOT_FOUND,"资源不存在"); }
    @ExceptionHandler({IllegalArgumentException.class,ConstraintViolationException.class,MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return problem(HttpStatus.BAD_REQUEST,e instanceof IllegalArgumentException ? e.getMessage() : "请求参数无效，请检查字段、长度与取值范围");
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(Exception e) { return problem(HttpStatus.PAYLOAD_TOO_LARGE,"上传文件不能超过20 MiB"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse error) {
            return problem(error.getStatusCode(), "请求的资源或方法不可用");
        }
        log.warn("request_failed requestId={} errorType={}",MDC.get("requestId"),e.getClass().getSimpleName());
        return problem(e instanceof IllegalStateException ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
            "服务暂时不可用，请检查配置或使用 requestId 联系维护人员");
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatusCode status,String detail) {
        var body=ProblemDetail.forStatusAndDetail(status,detail);
        body.setProperty("requestId",MDC.get("requestId"));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
