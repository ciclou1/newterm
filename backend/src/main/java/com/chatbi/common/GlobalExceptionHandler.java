package com.chatbi.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ApiError(OffsetDateTime timestamp, int status, String code, String message, String requestId) {
    }

    @ExceptionHandler(ChatBiException.class)
    public ResponseEntity<ApiError> handleChatBi(ChatBiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(error(ex.getStatus().value(), ex.getCode(), ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(error(400, "VALIDATION_ERROR", message, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(error(500, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试", request));
    }

    private ApiError error(int status, String code, String message, HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return new ApiError(OffsetDateTime.now(), status, code, message,
                requestId == null ? null : requestId.toString());
    }
}
