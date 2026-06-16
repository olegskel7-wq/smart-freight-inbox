package com.smartfreight.inbox.config;

import com.smartfreight.inbox.tenancy.TenantClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authenticates and authorizes STOMP frames on the inbound channel (Phase 3.2).
 *
 * <p>The browser WebSocket handshake cannot send an {@code Authorization} header, so the {@code /ws}
 * handshake is {@code permitAll} at the HTTP layer and identity is established here on the STOMP
 * {@code CONNECT} frame instead:
 * <ul>
 *   <li><b>CONNECT</b> — read the {@code Authorization: Bearer <jwt>} native header, validate it with
 *       the same {@link JwtDecoder} the {@code /api} resource server uses (issuer/JWKS/signature),
 *       map roles with {@link KeycloakJwtRoleConverter} and attach the resulting
 *       {@link JwtAuthenticationToken} as the session user. A missing or invalid token rejects the
 *       connection.</li>
 *   <li><b>SUBSCRIBE</b> — for the per-tenant ticket topic ({@code /topic/tickets/{tenantId}}),
 *       enforce that the requested tenant segment equals the connected user's {@code tenant_id}
 *       claim. This is defence in depth on top of the broadcast being per-tenant: a client can never
 *       subscribe to another tenant's stream even if it crafts the destination by hand.</li>
 * </ul>
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    /** Per-tenant ticket topic prefix; the segment after it is the tenant id. */
    static final String TICKET_TOPIC_PREFIX = "/topic/tickets/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final KeycloakJwtRoleConverter roleConverter = new KeycloakJwtRoleConverter();

    public StompAuthChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    /** Validates the bearer token on CONNECT and binds the authenticated principal to the session. */
    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessageDeliveryException("Missing bearer token on STOMP CONNECT");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()).trim());
        } catch (JwtException e) {
            log.warn("Rejected STOMP CONNECT — invalid bearer token: {}", e.getMessage());
            throw new MessageDeliveryException("Invalid bearer token on STOMP CONNECT");
        }
        accessor.setUser(new JwtAuthenticationToken(jwt, roleConverter.convert(jwt)));
    }

    /** Rejects a SUBSCRIBE to another tenant's ticket topic (the user's token decides the tenant). */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TICKET_TOPIC_PREFIX)) {
            return; // not a per-tenant ticket topic — nothing tenant-scoped to guard
        }
        Long tenantId = tenantOf(accessor);
        String requested = destination.substring(TICKET_TOPIC_PREFIX.length());
        if (tenantId == null || !requested.equals(String.valueOf(tenantId))) {
            log.warn("Rejected STOMP SUBSCRIBE to {} — token tenant {}", destination, tenantId);
            throw new MessageDeliveryException("Forbidden ticket topic for this tenant");
        }
    }

    /** Tenant id from the connected user's JWT, or {@code null} when absent/unauthenticated. */
    private Long tenantOf(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof JwtAuthenticationToken auth) {
            return TenantClaims.fromJwt(auth.getToken());
        }
        return null;
    }
}
