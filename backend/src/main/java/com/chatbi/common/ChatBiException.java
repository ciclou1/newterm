package com.chatbi.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ChatBiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ChatBiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static ChatBiException badRequest(String code, String message) {
        return new ChatBiException(code, message, HttpStatus.BAD_REQUEST);
    }

    public static ChatBiException notFound(String message) {
        return new ChatBiException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    public static ChatBiException forbidden(String code, String message) {
        return new ChatBiException(code, message, HttpStatus.FORBIDDEN);
    }
}
