package com.instacommerce.inventory.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.springframework.security.core.GrantedAuthority;
import java.util.Collection;

public interface JwtService {
    Jws<Claims> validateAccessToken(String token);

    Collection<? extends GrantedAuthority> extractAuthorities(Claims claims);
}
