package com.chatbi.security;

public class JwtValidationException extends RuntimeException {

    private final String code;

    public JwtValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
