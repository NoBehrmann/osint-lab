package com.osintlab.tools;

/** Thrown when no execution slot frees up in time; mapped to HTTP 429. */
public class ToolBusyException extends RuntimeException {

    public ToolBusyException(String message) {
        super(message);
    }
}
