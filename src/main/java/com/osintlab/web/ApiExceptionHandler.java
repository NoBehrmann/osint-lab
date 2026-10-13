package com.osintlab.web;

import com.osintlab.support.ToolUnavailableException;
import com.osintlab.support.UpstreamException;
import com.osintlab.tools.ToolBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Turns tool failures into {"error": "..."} JSON the frontend can display. */
@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "File too large (max 1 GB)");
    }

    /** Usually the user pressed Stop (or closed the tab) mid-upload. Expected, not worth a stack trace. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> brokenUpload(MultipartException e) {
        log.debug("Upload interrupted", e);
        return error(HttpStatus.BAD_REQUEST, "Upload interrupted, choose the file and run again");
    }

    @ExceptionHandler(ToolBusyException.class)
    public ResponseEntity<Map<String, String>> busy(ToolBusyException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(UnknownHostException.class)
    public ResponseEntity<Map<String, String>> unknownHost(UnknownHostException e) {
        return error(HttpStatus.BAD_REQUEST, "Host could not be resolved: " + e.getMessage());
    }

    @ExceptionHandler(HttpTimeoutException.class)
    public ResponseEntity<Map<String, String>> timeout(HttpTimeoutException e) {
        return error(HttpStatus.GATEWAY_TIMEOUT, "Upstream service timed out");
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Map<String, String>> toolTimeout(TimeoutException e) {
        return error(HttpStatus.GATEWAY_TIMEOUT, e.getMessage());
    }

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<Map<String, String>> upstream(UpstreamException e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(ToolUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(ToolUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        log.warn("Tool failed", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
