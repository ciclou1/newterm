package com.chatbi.security;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal endpoints mirroring the protected URL space, so security tests can
 * exercise the filter chain without booting controllers or a database.
 */
@RestController
public class SecurityProbeController {

    @GetMapping("/api/conversations/probe")
    public Map<String, String> business() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/users/probe")
    public Map<String, String> users() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/semantic/permissions/probe")
    public Map<String, String> permissions() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/semantic/probe")
    public Map<String, String> semanticWrite() {
        return Map.of("status", "ok");
    }

    @PutMapping("/api/semantic/probe")
    public Map<String, String> semanticUpdate() {
        return Map.of("status", "ok");
    }

    @DeleteMapping("/api/semantic/probe")
    public Map<String, String> semanticDelete() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/eval/probe")
    public Map<String, String> evaluation() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/chat/query")
    public Map<String, String> chat(@RequestBody(required = false) String body) {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/auth/login")
    public Map<String, String> login(@RequestBody(required = false) String body) {
        return Map.of("status", "ok");
    }
}
