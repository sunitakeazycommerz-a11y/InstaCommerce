package com.instacommerce.fulfillment.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;

public interface JwtService {
    Jws<Claims> validateAccessToken(String token);

    Collection<? extends GrantedAuthority> extractAuthorities(Claims claims);
}
