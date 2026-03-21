package com.instacommerce.admingateway.config;

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import com.instacommerce.admingateway.security.JwtKeyLoader;

@TestConfiguration
@ActiveProfiles("test")
public class AdminGatewayTestConfig {

  @Bean
  @Primary
  public JwtKeyLoader testJwtKeyLoader() throws Exception {
    String publicKeyPem = new String(Files.readAllBytes(
        Paths.get("src/test/resources/test-public-key.pem")));
    PublicKey publicKey = parsePublicKey(publicKeyPem);

    JwtKeyLoader mockKeyLoader = mock(JwtKeyLoader.class);
    when(mockKeyLoader.getPublicKey()).thenReturn((java.security.interfaces.RSAPublicKey) publicKey);
    return mockKeyLoader;
  }

  private static PublicKey parsePublicKey(String keyPem) throws Exception {
    String normalized = keyPem
        .replaceAll("-----BEGIN PUBLIC KEY-----", "")
        .replaceAll("-----END PUBLIC KEY-----", "")
        .replaceAll("\\s", "");
    byte[] decoded = Base64.getDecoder().decode(normalized);
    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
    return KeyFactory.getInstance("RSA").generatePublic(keySpec);
  }
}

