package com.instacommerce.identity.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/.well-known")
public class JwksController {
    private final JwtKeyLoader keyLoader;

    public JwksController(JwtKeyLoader keyLoader) {
        this.keyLoader = keyLoader;
    }

    @GetMapping("/jwks.json")
    public Map<String, Object> jwks() {
        RSAPublicKey publicKey = keyLoader.getPublicKey();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", keyLoader.getKeyId());
        jwk.put("n", toBase64Url(publicKey.getModulus()));
        jwk.put("e", toBase64Url(publicKey.getPublicExponent()));
        return Map.of("keys", List.of(jwk));
    }

    private static String toBase64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
