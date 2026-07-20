package org.cj.server.realtime.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import org.cj.server.realtime.security.StompAuthChannelInterceptor;

/**
 * The real-time transport (M5): a STOMP-over-WebSocket endpoint at {@code /ws}, with Spring's
 * built-in <b>simple broker</b> fanning messages out to {@code /topic/board/{boardId}}. No
 * Redis, no RabbitMQ — the Boot process holds the connections itself, which is the right call
 * at this scale (≤10 concurrent users per board) and is why the app is deployed to a
 * persistent host rather than serverless.
 *
 * <p>Two deliberate omissions:
 *
 * <ul>
 *   <li><b>No application destination prefix.</b> Clients never SEND anything — every change
 *       goes through the authenticated REST API, and the socket is a one-way feed out. Leaving
 *       {@code /app} unconfigured means an inbound SEND has nowhere to route, so the write path
 *       cannot be reached without passing the normal HTTP guards.</li>
 *   <li><b>No SockJS fallback.</b> Every browser we target speaks native WebSocket; the
 *       fallback would add an HTTP polling surface for nothing.</li>
 * </ul>
 *
 * <p>Authentication is not configured here but in {@link StompAuthChannelInterceptor}, which is
 * registered on the inbound channel below — the handshake itself is anonymous by necessity
 * (browsers can't set headers on a WebSocket upgrade), so identity arrives one frame later on
 * STOMP CONNECT.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Same origins the REST API accepts — the socket must not be laxer than the API. */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    private final StompAuthChannelInterceptor authInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
