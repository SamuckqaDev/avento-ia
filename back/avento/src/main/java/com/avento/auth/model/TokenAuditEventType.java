package com.avento.auth.model;

public enum TokenAuditEventType {
    BOOTSTRAP_USER,
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    TOKEN_REFRESH,
    REFRESH_FAILED,
    LOGOUT,
    SESSION_REVOKED
}
