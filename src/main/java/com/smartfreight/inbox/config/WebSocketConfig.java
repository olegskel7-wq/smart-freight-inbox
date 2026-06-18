package com.smartfreight.inbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration for real-time ticket updates (Phase 3.2 — React migration).
 *
 * <p>The backend pushes a lightweight {@code TicketLiveEvent} to a per-tenant topic
 * ({@code /topic/tickets/{tenantId}}) whenever a ticket is created, updated or soft-deleted (see
 * {@link com.smartfreight.inbox.messaging.TicketLiveEventListener}); the React {@code /tickets} list
 * subscribes and re-fetches the current query on each event, so operators see new mail and status
 * changes without polling or a manual refresh.
 *
 * <p><b>Auth.</b> The browser's WebSocket handshake cannot carry an {@code Authorization} header, so
 * the {@code /ws} handshake is {@code permitAll} at the HTTP layer (see {@code SecurityConfig}) and
 * authentication happens on the STOMP {@code CONNECT} frame instead, in
 * {@link StompAuthChannelInterceptor}: it validates the same Keycloak bearer JWT the {@code /api}
 * resource server uses and, on {@code SUBSCRIBE}, enforces that a client only joins its own tenant's
 * topic. We deliberately do <i>not</i> enable {@code @EnableWebSocketSecurity} — there is no inbound
 * application message to authorize (clients only subscribe; the server only broadcasts), so the
 * channel interceptor is the whole security story and we avoid its CSRF-on-CONNECT requirement.
 *
 * <p>A {@link org.springframework.messaging.simp.config.MessageBrokerRegistry#enableSimpleBroker
 * simple in-memory broker} is enough: a single backend instance fans events out to connected
 * operators. (A multi-instance deploy would need a broker relay or a Redis-backed relay — deferred,
 * same horizontal-scaling note as the rest of Phase 2/3.)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authChannelInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor authChannelInterceptor) {
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Raw WebSocket (no SockJS fallback): the SPA targets modern browsers and is served
        // same-origin (dev via the Vite /ws proxy, prod via nginx) — no cross-origin handshake.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Server → client broadcasts only. No application destination prefix is configured because
        // there are no @MessageMapping handlers — clients never send messages, they only subscribe.
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Authenticate CONNECT and authorize SUBSCRIBE on the inbound channel.
        registration.interceptors(authChannelInterceptor);
    }
}
