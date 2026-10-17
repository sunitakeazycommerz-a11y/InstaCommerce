package com.instacommerce.admingateway.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminHealthController {

    @GetMapping("/admin/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}

