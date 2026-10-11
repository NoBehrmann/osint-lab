package com.osintlab.support;

/** An external service or tool failed or returned something unusable; mapped to HTTP 502. */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }
}
