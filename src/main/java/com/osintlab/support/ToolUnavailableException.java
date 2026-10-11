package com.osintlab.support;

/** A tool's dependency (e.g. a Python CLI) is missing; mapped to HTTP 503. */
public class ToolUnavailableException extends RuntimeException {

    public ToolUnavailableException(String message) {
        super(message);
    }
}
