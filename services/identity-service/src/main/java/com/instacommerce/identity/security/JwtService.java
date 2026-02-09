package com.instacommerce.identity.security;

import com.instacommerce.identity.domain.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public interface JwtService {
    String generateAccessToken(User user);

    Jws<Claims> validateAccessToken(String token);

    Collection<? extends GrantedAuthority> extractAuthorities(Claims claims);
}
