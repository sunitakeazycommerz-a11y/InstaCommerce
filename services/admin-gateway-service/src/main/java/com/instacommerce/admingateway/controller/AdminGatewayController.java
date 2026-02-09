package com.instacommerce.admingateway.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1")
public class AdminGatewayController {
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of("status", "ok");
    }
}
