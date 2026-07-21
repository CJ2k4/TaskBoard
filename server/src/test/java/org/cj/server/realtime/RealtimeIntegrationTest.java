package org.cj.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack tests for M5 real-time. These drive a <b>real</b> STOMP client over a <b>real</b>
 * WebSocket against a running server, because the thing under test is exactly what a browser
 * does: connect, prove who you are, subscribe to a board, receive other people's changes.
 * Asserting that {@code BoardEventBroadcaster} calls a mock template would test the wiring
 * diagram rather than the wiring.
 *
 * <p>The REST half still goes through MockMvc, which shares this application context — so a
 * MockMvc write really does publish through the real event listener into the real broker and
 * out to the socket. Only the transport differs.
 *
 * <p>Two properties get equal billing here, and the second is the one that bites: changes
 * must <b>reach members</b>, and they must <b>not reach anyone else</b>. A live feed that
 * ignores the permission model is a worse leak than a missing endpoint, because nothing in the
 * REST tests would ever catch it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RealtimeIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    /** How long to wait for a message that should arrive. Generous: failure here is a hang. */
    private static final long EXPECT_TIMEOUT_MS = 5_000;

    /** How long to wait to be convinced a message will *not* arrive. */
    private static final long SILENCE_TIMEOUT_MS = 800;

    // ---------------------------------------------------------------- fixtures

    private record TestUser(String id, String token, String refreshToken, String email) {}

    private TestUser newUser() throws Exception {
        String email = "rt-" + UUID.randomUUID() + "@example.com";
        JsonNode body = json(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"hunter2secret","name":"Ada"}"""
                                .formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return new TestUser(
                body.get("user").get("id").asText(),
                body.get("accessToken").asText(),
                body.get("refreshToken").asText(),
                email);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, TestUser user) {
        return b.header("Authorization", "Bearer " + user.token());
    }

    private JsonNode json(String body) throws Exception {
        return om.readTree(body);
    }

    private String createBoard(TestUser owner, String name) throws Exception {
        return json(mvc.perform(auth(post("/api/boards"), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String createColumn(TestUser user, String boardId, String title) throws Exception {
        return json(mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String createCard(TestUser user, String columnId, String title) throws Exception {
        return json(mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private void invite(TestUser owner, String boardId, TestUser invitee, String role) throws Exception {
        mvc.perform(auth(post("/api/boards/" + boardId + "/invites"), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitee.email() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- socket plumbing

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // Jackson, and frames are taken as an untyped JsonNode: the assertions then read the
        // JSON a browser would actually receive, field names and all, instead of a Java object
        // that a shared DTO class could quietly keep in agreement with itself.
        //
        // Note this must be a converter that accepts application/json — the default
        // StringMessageConverter handles text/plain only and drops every board event on the
        // floor without a word.
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    /** Open an authenticated STOMP session, the way the frontend will. */
    private StompSession connect(TestUser user) throws Exception {
        return connectWithToken("Bearer " + user.token());
    }

    private StompSession connectWithToken(String authorizationHeader) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (authorizationHeader != null) {
            connectHeaders.add("Authorization", authorizationHeader);
        }
        return stompClient().connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    /**
     * Subscribe to a board's topic and return a queue of the events that arrive on it.
     *
     * <p>The sleep is load-bearing, not superstition: {@code subscribe} only puts a SUBSCRIBE
     * frame on the wire and returns, so without a pause the REST write below could be
     * broadcast before the server has registered the subscription — and the test would fail
     * on a race that never happens in a real session (where a user opens a board and then
     * things happen to it).
     */
    private BlockingQueue<JsonNode> subscribe(StompSession session, String boardId) throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/board/" + boardId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        Thread.sleep(400);
        return received;
    }

    /**
     * The next <em>domain</em> event, skipping PRESENCE frames. Presence (M6) is broadcast on the
     * same topic whenever someone subscribes or leaves, so a test watching for card/column/member
     * changes must look past it — the assertion is about what a mutation announces, not about who
     * happens to be viewing.
     */
    private JsonNode nextEvent(BlockingQueue<JsonNode> queue) throws Exception {
        long deadline = System.currentTimeMillis() + EXPECT_TIMEOUT_MS;
        for (;;) {
            long remaining = deadline - System.currentTimeMillis();
            JsonNode event = remaining > 0 ? queue.poll(remaining, TimeUnit.MILLISECONDS) : null;
            assertThat(event).as("expected a board event but the topic stayed silent").isNotNull();
            if (!isPresence(event)) {
                return event;
            }
        }
    }

    /** The next PRESENCE event, skipping any domain frames. */
    private JsonNode nextPresence(BlockingQueue<JsonNode> queue) throws Exception {
        long deadline = System.currentTimeMillis() + EXPECT_TIMEOUT_MS;
        for (;;) {
            long remaining = deadline - System.currentTimeMillis();
            JsonNode event = remaining > 0 ? queue.poll(remaining, TimeUnit.MILLISECONDS) : null;
            assertThat(event).as("expected a PRESENCE event but the topic stayed silent").isNotNull();
            if (isPresence(event)) {
                return event;
            }
        }
    }

    /** No <em>domain</em> event may arrive; PRESENCE noise is ignored. */
    private void assertSilent(BlockingQueue<JsonNode> queue) throws Exception {
        long deadline = System.currentTimeMillis() + SILENCE_TIMEOUT_MS;
        for (;;) {
            long remaining = deadline - System.currentTimeMillis();
            JsonNode event = remaining > 0 ? queue.poll(remaining, TimeUnit.MILLISECONDS) : null;
            if (event == null) {
                return; // stayed silent of domain events for the whole window
            }
            assertThat(event.get("type").asText())
                    .as("expected no domain event to arrive")
                    .isEqualTo("PRESENCE");
        }
    }

    private boolean isPresence(JsonNode event) {
        return event.get("type").asText().equals("PRESENCE");
    }

    /** The user ids in a PRESENCE event's payload. */
    private List<String> viewerIds(JsonNode presence) {
        List<String> ids = new ArrayList<>();
        presence.get("payload").forEach(v -> ids.add(v.get("userId").asText()));
        return ids;
    }

    // ---------------------------------------------------------------- delivery

    @Test
    void memberReceivesCardLifecycleEventsFromAnotherUser() throws Exception {
        TestUser owner = newUser();
        TestUser editor = newUser();
        String boardId = createBoard(owner, "Live");
        String columnId = createColumn(owner, boardId, "To Do");
        invite(owner, boardId, editor, "EDITOR");

        StompSession session = connect(editor);
        BlockingQueue<JsonNode> events = subscribe(session, boardId);

        // The owner works on the board while the editor watches.
        String cardId = createCard(owner, columnId, "Ship it");

        JsonNode created = nextEvent(events);
        assertThat(created.get("type").asText()).isEqualTo("CARD_CREATED");
        assertThat(created.get("boardId").asText()).isEqualTo(boardId);
        // actorId is what lets a client recognize (and skip) the echo of its own change.
        assertThat(created.get("actorId").asText()).isEqualTo(owner.id());
        assertThat(created.get("payload").get("title").asText()).isEqualTo("Ship it");
        assertThat(created.get("payload").get("columnId").asText()).isEqualTo(columnId);

        mvc.perform(auth(patch("/api/cards/" + cardId), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ship it twice\"}"))
                .andExpect(status().isOk());
        JsonNode updated = nextEvent(events);
        assertThat(updated.get("type").asText()).isEqualTo("CARD_UPDATED");
        assertThat(updated.get("payload").get("title").asText()).isEqualTo("Ship it twice");

        mvc.perform(auth(patch("/api/cards/" + cardId + "/move"), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetColumnId\":\"" + columnId + "\"}"))
                .andExpect(status().isOk());
        JsonNode moved = nextEvent(events);
        assertThat(moved.get("type").asText()).isEqualTo("CARD_MOVED");
        // The server's resolved rank rides along — it's what the client reconciles to.
        assertThat(moved.get("payload").get("rank").asText()).isNotBlank();

        mvc.perform(auth(delete("/api/cards/" + cardId), owner)).andExpect(status().isNoContent());
        JsonNode deleted = nextEvent(events);
        assertThat(deleted.get("type").asText()).isEqualTo("CARD_DELETED");
        assertThat(deleted.get("payload").get("id").asText()).isEqualTo(cardId);

        session.disconnect();
    }

    @Test
    void memberReceivesColumnAndBoardEvents() throws Exception {
        TestUser owner = newUser();
        TestUser viewer = newUser();
        String boardId = createBoard(owner, "Live");
        invite(owner, boardId, viewer, "VIEWER");

        // A viewer can't write, but absolutely must see others' writes — read-only is not
        // the same as offline.
        StompSession session = connect(viewer);
        BlockingQueue<JsonNode> events = subscribe(session, boardId);

        String columnId = createColumn(owner, boardId, "To Do");
        JsonNode columnCreated = nextEvent(events);
        assertThat(columnCreated.get("type").asText()).isEqualTo("COLUMN_CREATED");
        assertThat(columnCreated.get("payload").get("title").asText()).isEqualTo("To Do");

        mvc.perform(auth(delete("/api/columns/" + columnId), owner)).andExpect(status().isNoContent());
        JsonNode columnDeleted = nextEvent(events);
        assertThat(columnDeleted.get("type").asText()).isEqualTo("COLUMN_DELETED");
        assertThat(columnDeleted.get("payload").get("id").asText()).isEqualTo(columnId);

        mvc.perform(auth(patch("/api/boards/" + boardId), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed live\"}"))
                .andExpect(status().isOk());
        JsonNode boardUpdated = nextEvent(events);
        assertThat(boardUpdated.get("type").asText()).isEqualTo("BOARD_UPDATED");
        assertThat(boardUpdated.get("payload").get("name").asText()).isEqualTo("Renamed live");
        // The broadcast board payload carries no myRole — a role belongs to one member, and
        // this message goes to all of them.
        assertThat(boardUpdated.get("payload").has("myRole")).isFalse();

        mvc.perform(auth(delete("/api/boards/" + boardId), owner)).andExpect(status().isNoContent());
        JsonNode boardDeleted = nextEvent(events);
        assertThat(boardDeleted.get("type").asText()).isEqualTo("BOARD_DELETED");
        assertThat(boardDeleted.get("payload").get("id").asText()).isEqualTo(boardId);

        session.disconnect();
    }

    @Test
    void removedMemberIsToldWhoWasRemoved() throws Exception {
        TestUser owner = newUser();
        TestUser editor = newUser();
        String boardId = createBoard(owner, "Live");
        invite(owner, boardId, editor, "EDITOR");

        StompSession session = connect(editor);
        BlockingQueue<JsonNode> events = subscribe(session, boardId);

        String membershipId = null;
        JsonNode members = json(mvc.perform(auth(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/boards/" + boardId + "/members"), owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode m : members) {
            if (m.get("role").asText().equals("EDITOR")) {
                membershipId = m.get("id").asText();
            }
        }
        assertThat(membershipId).isNotNull();

        mvc.perform(auth(delete("/api/memberships/" + membershipId), owner))
                .andExpect(status().isNoContent());

        JsonNode removed = nextEvent(events);
        assertThat(removed.get("type").asText()).isEqualTo("MEMBER_REMOVED");
        // The userId is the point: it's how the editor recognizes that they are the one who
        // just lost access, rather than some other member.
        assertThat(removed.get("payload").get("userId").asText()).isEqualTo(editor.id());

        session.disconnect();
    }

    @Test
    void eventsGoOnlyToTheirOwnBoardsTopic() throws Exception {
        TestUser owner = newUser();
        String watched = createBoard(owner, "Watched");
        String other = createBoard(owner, "Other");
        String otherColumn = createColumn(owner, other, "Elsewhere");

        StompSession session = connect(owner);
        BlockingQueue<JsonNode> events = subscribe(session, watched);

        // Busy work on a board this subscriber isn't watching — even though it's the *same
        // user*, subscribed to a different topic.
        createCard(owner, otherColumn, "Not yours");
        assertSilent(events);

        session.disconnect();
    }

    // ---------------------------------------------------------------- presence (M6)

    @Test
    void viewersSeeEachOtherComeAndGo() throws Exception {
        TestUser owner = newUser();
        TestUser editor = newUser();
        String boardId = createBoard(owner, "Live");
        invite(owner, boardId, editor, "EDITOR");

        // The owner opens the board first: presence lists them alone.
        StompSession ownerSession = connect(owner);
        BlockingQueue<JsonNode> ownerEvents = subscribe(ownerSession, boardId);
        assertThat(viewerIds(nextPresence(ownerEvents))).containsExactly(owner.id());

        // The editor opens it too: both sockets learn the two-viewer set.
        StompSession editorSession = connect(editor);
        BlockingQueue<JsonNode> editorEvents = subscribe(editorSession, boardId);
        assertThat(viewerIds(nextPresence(ownerEvents)))
                .containsExactlyInAnyOrder(owner.id(), editor.id());
        assertThat(viewerIds(nextPresence(editorEvents)))
                .containsExactlyInAnyOrder(owner.id(), editor.id());

        // The editor closes the board: the owner sees them drop out.
        editorSession.disconnect();
        assertThat(viewerIds(nextPresence(ownerEvents))).containsExactly(owner.id());

        ownerSession.disconnect();
    }

    // ---------------------------------------------------------------- refusal

    /**
     * A CONNECT the interceptor refuses must not yield a usable session.
     *
     * <p>The assertion is on the refusal, not on the server's wording, because the client can't
     * reliably observe the wording: the server answers with an ERROR frame and closes the
     * socket, and the close routinely wins the race, so the client sees
     * {@code ConnectionLostException} instead. That's fine — a caller who cannot obtain a
     * session cannot hear a board either way, and the exact reason is deliberately vague on the
     * wire anyway.
     */
    private void assertConnectRefused(String authorizationHeader) {
        assertThatThrownBy(() -> connectWithToken(authorizationHeader))
                .as("a rejected CONNECT must not produce a session")
                .isNotNull();
    }

    @Test
    void connectWithoutAnAccessTokenIsRejected() {
        assertConnectRefused(null);
    }

    @Test
    void connectWithAGarbageTokenIsRejected() {
        assertConnectRefused("Bearer not-a-jwt");
    }

    @Test
    void connectWithARefreshTokenIsRejected() throws Exception {
        TestUser user = newUser();
        // A refresh token is a valid, correctly signed JWT — it just isn't a key to the API,
        // and must not become one here.
        assertConnectRefused("Bearer " + user.refreshToken());
    }

    @Test
    void nonMemberCannotSubscribeToABoardTopic() throws Exception {
        TestUser owner = newUser();
        TestUser stranger = newUser();
        String boardId = createBoard(owner, "Private");
        String columnId = createColumn(owner, boardId, "To Do");

        // The stranger is a legitimately signed-in user — they just have no business here.
        StompSession session = connect(stranger);
        BlockingQueue<JsonNode> events = subscribe(session, boardId);

        // The refusal is terminal: the server answers the SUBSCRIBE with an ERROR frame and
        // closes the session, so there is no subscription left to leak through.
        assertThat(session.isConnected()).isFalse();

        createCard(owner, columnId, "Secret");
        assertSilent(events);
    }

    @Test
    void subscribingToAnUnknownDestinationIsRejected() throws Exception {
        TestUser user = newUser();
        StompSession session = connect(user);

        session.subscribe("/topic/everything", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // no-op; the point is that we never get here
            }
        });
        Thread.sleep(400);

        assertThat(session.isConnected()).isFalse();
    }
}
