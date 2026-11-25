package com.instacommerce.routing.config;

import com.instacommerce.routing.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String DEFAULT_ALLOWED_ORIGINS =
        "http://localhost:3000,https://*.instacommerce.dev";

    private final JwtService jwtService;
    private final String[] allowedOriginPatterns;

    @Autowired
    public WebSocketConfig(
            JwtService jwtService,
            @Value("${routing.ws.allowed-origins:${routing.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}}")
            String allowedOrigins) {
        this.jwtService = jwtService;
        String raw = (allowedOrigins == null || allowedOrigins.isBlank())
            ? DEFAULT_ALLOWED_ORIGINS
            : allowedOrigins;
        this.allowedOriginPatterns = Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/tracking")
            .setAllowedOriginPatterns(allowedOriginPatterns)
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompJwtChannelInterceptor());
    }

    public ChannelInterceptor stompJwtChannelInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }
                StompCommand command = accessor.getCommand();
                if (StompCommand.CONNECT.equals(command)) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader == null) {
                        authHeader = accessor.getFirstNativeHeader("authorization");
                    }
                    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                        throw new AccessDeniedException("Missing Bearer token on STOMP CONNECT");
                    }
                    String token = authHeader.substring(7).trim();
                    if (token.isEmpty()) {
                        throw new AccessDeniedException("Blank Bearer token on STOMP CONNECT");
                    }
                    try {
                        Jws<Claims> jws = jwtService.validateAccessToken(token);
                        UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                jws.getPayload().getSubject(),
                                null,
                                jwtService.extractAuthorities(jws.getPayload()));
                        accessor.setUser(auth);
                    } catch (JwtException | IllegalArgumentException ex) {
                        throw new AccessDeniedException("Invalid JWT token on STOMP CONNECT", ex);
                    }
                } else if (StompCommand.SUBSCRIBE.equals(command) || StompCommand.SEND.equals(command)) {
                    if (accessor.getUser() == null) {
                        throw new AccessDeniedException("Unauthenticated STOMP frame: " + command);
                    }
                }
                return message;
            }
        };
    }
}
