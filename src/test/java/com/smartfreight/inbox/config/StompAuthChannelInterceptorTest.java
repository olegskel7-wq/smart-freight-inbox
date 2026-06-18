package com.smartfreight.inbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StompAuthChannelInterceptor} (Phase 3.2): CONNECT must carry a valid bearer
 * token, and SUBSCRIBE must target the caller's own tenant topic.
 */
class StompAuthChannelInterceptorTest {

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtDecoder);

    private Jwt jwt(Long tenantId) {
        return Jwt.withTokenValue("token").header("alg", "none").subject("op@x.com")
                .claim("tenant_id", tenantId)
                .claim("roles", List.of("OPERATOR"))
                .build();
    }

    /** Builds a mutable STOMP message so we can read back the principal the interceptor sets. */
    private Message<byte[]> connectMessage(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (authHeader != null) {
            accessor.addNativeHeader("Authorization", authHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, JwtAuthenticationToken user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);
        if (user != null) {
            accessor.setUser(user);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private StompHeaderAccessor accessorOf(Message<?> message) {
        return StompHeaderAccessor.wrap(message);
    }

    @Test
    void connectWithoutBearerIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage(null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connectWithMalformedHeaderIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage("token-without-bearer"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        when(jwtDecoder.decode("bad")).thenThrow(new JwtException("expired"));

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer bad"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void connectWithValidTokenSetsJwtPrincipal() {
        when(jwtDecoder.decode("good")).thenReturn(jwt(7L));

        Message<byte[]> message = connectMessage("Bearer good");
        interceptor.preSend(message, null);

        assertThat(accessorOf(message).getUser()).isInstanceOf(JwtAuthenticationToken.class);
    }

    @Test
    void subscribeToOwnTenantTopicIsAllowed() {
        JwtAuthenticationToken user = new JwtAuthenticationToken(jwt(7L));

        // No exception thrown.
        interceptor.preSend(subscribeMessage("/topic/tickets/7", user), null);
    }

    @Test
    void subscribeToAnotherTenantTopicIsRejected() {
        JwtAuthenticationToken user = new JwtAuthenticationToken(jwt(7L));

        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("/topic/tickets/8", user), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void subscribeToTicketTopicWithoutPrincipalIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("/topic/tickets/7", null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void subscribeToNonTicketTopicIsIgnored() {
        // A destination outside the per-tenant ticket namespace is not guarded here.
        interceptor.preSend(subscribeMessage("/topic/other", null), null);
    }
}
