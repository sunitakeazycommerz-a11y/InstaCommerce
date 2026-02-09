package com.instacommerce.mobilebff.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping({"/bff/mobile/v1", "/m/v1"})
public class MobileBffController {
    @GetMapping("/home")
    public Mono<Map<String, Object>> home() {
        return Mono.just(Map.of("status", "ok"));
    }
}
