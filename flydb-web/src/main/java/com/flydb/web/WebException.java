package com.flydb.web;

/** Stable local UI error code; never used as a database-domain error. */
final class WebException extends RuntimeException {
    final int status;
    final String code;
    WebException(int status, String code, String detail) { super(detail); this.status = status; this.code = code; }
}
