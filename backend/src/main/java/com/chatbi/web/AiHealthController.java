package com.chatbi.web;

import com.chatbi.health.AiHealthIndicator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight, key-free AI diagnostics for operators and smoke tests.
 * Actuator health exposes the same snapshot under component {@code ai}.
 */
@RestController
@RequestMapping("/api/health")
public class AiHealthController {

    private final AiHealthIndicator indicator;

    public AiHealthController(AiHealthIndicator indicator) {
        this.indicator = indicator;
    }

    @GetMapping("/ai")
    public Map<String, Object> ai() {
        return indicator.snapshot();
    }
}
