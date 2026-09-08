package com.mentra.asg_client.io.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.mentra.asg_client.AsgConstants;
import okhttp3.Request;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Deterministic tests for the direct WebSocket protocol, backoff, and message-rate cap. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DirectWebSocketTransportTest {

    /** Repeated lifecycle calls do not register work twice or revive a stopped instance. */
    @Test
    public void disabledLifecycleIsIdempotent() {
        Context context = ApplicationProvider.getApplicationContext();
        DirectWebSocketTransport transport =
                new DirectWebSocketTransport(
                        context,
                        DirectServerConfig.create("disabled", "", "", "", "", false, true),
                        new DirectDeviceStatusProvider(context, null, null));

        transport.start();
        transport.start();
        assertTrue(transport.isStarted());

        transport.stop();
        transport.stop();
        assertFalse(transport.isStarted());

        transport.start();
        assertFalse(transport.isStarted());
    }

    /** A Cloudflare-protected WebSocket upgrade carries both service-token headers. */
    @Test
    public void cloudflareAccessHeadersAreAddedToUpgradeRequest() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect",
                        "",
                        "service-token-id.access",
                        "service-token-secret",
                        false,
                        false);

        Request request = DirectWebSocketTransport.buildWebSocketRequest(config);

        assertEquals(
                "service-token-id.access",
                request.header(AsgConstants.DIRECT_SERVER_CF_ACCESS_CLIENT_ID_HEADER));
        assertEquals(
                "service-token-secret",
                request.header(AsgConstants.DIRECT_SERVER_CF_ACCESS_CLIENT_SECRET_HEADER));
        assertNull(request.header(AsgConstants.DIRECT_SERVER_DEVICE_TOKEN_HEADER));
    }

    /** Cloudflare edge auth and the legacy development token can be sent together. */
    @Test
    public void upgradeRequestCanCarryBothAuthenticationLayers() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect",
                        "temporary-test-token",
                        "service-token-id.access",
                        "service-token-secret",
                        false,
                        false);

        Request request = DirectWebSocketTransport.buildWebSocketRequest(config);

        assertEquals(
                "temporary-test-token",
                request.header(AsgConstants.DIRECT_SERVER_DEVICE_TOKEN_HEADER));
        assertEquals(
                "service-token-id.access",
                request.header(AsgConstants.DIRECT_SERVER_CF_ACCESS_CLIENT_ID_HEADER));
        assertEquals(
                "service-token-secret",
                request.header(AsgConstants.DIRECT_SERVER_CF_ACCESS_CLIENT_SECRET_HEADER));
    }

    /** Ping replies preserve the optional request correlation identifier. */
    @Test
    public void pingBuildsCorrelatedPong() throws Exception {
        DirectWebSocketTransport.InboundCommand command =
                DirectWebSocketTransport.parseInboundMessage(
                        "{\"type\":\"ping\",\"requestId\":\"request-7\","
                                + "\"timestamp\":1,\"payload\":{}}");

        JSONObject reply =
                DirectWebSocketTransport.buildEnvelope(
                        "pong", command.mRequestId, new JSONObject(), 1234L);

        assertEquals(DirectWebSocketTransport.InboundType.PING, command.mType);
        assertEquals("pong", reply.getString("type"));
        assertEquals("request-7", reply.getString("requestId"));
        assertEquals(1234L, reply.getLong("timestamp"));
        assertEquals(0, reply.getJSONObject("payload").length());
    }

    /** Status requests without correlation remain valid and omit the response field. */
    @Test
    public void statusAllowsMissingRequestId() throws Exception {
        DirectWebSocketTransport.InboundCommand command =
                DirectWebSocketTransport.parseInboundMessage(
                        "{\"type\":\"status\",\"timestamp\":1,\"payload\":{}}");
        JSONObject reply =
                DirectWebSocketTransport.buildEnvelope(
                        "status", command.mRequestId, new JSONObject(), 99L);

        assertEquals(DirectWebSocketTransport.InboundType.STATUS, command.mType);
        assertNull(command.mRequestId);
        assertFalse(reply.has("requestId"));
    }

    /** A valid server hello acknowledgement keeps the authenticated connection open. */
    @Test
    public void acceptedHelloAcknowledgementIsAllowed() throws Exception {
        DirectWebSocketTransport.InboundCommand command =
                DirectWebSocketTransport.parseInboundMessage(
                        "{\"type\":\"hello\",\"timestamp\":1,\"payload\":{"
                                + "\"accepted\":true,\"protocolVersion\":1,"
                                + "\"heartbeatTimeoutMs\":45000}}");

        assertEquals(DirectWebSocketTransport.InboundType.HELLO_ACK, command.mType);
    }

    /** Operational commands are gated until Cally accepts the application protocol. */
    @Test
    public void onlyHelloAcknowledgementIsAllowedBeforeProtocolReady() {
        assertTrue(
                DirectWebSocketTransport.isAllowedBeforeProtocolReady(
                        DirectWebSocketTransport.InboundType.HELLO_ACK));
        assertFalse(
                DirectWebSocketTransport.isAllowedBeforeProtocolReady(
                        DirectWebSocketTransport.InboundType.PING));
        assertFalse(
                DirectWebSocketTransport.isAllowedBeforeProtocolReady(
                        DirectWebSocketTransport.InboundType.STATUS));
    }

    /** A server protocol mismatch is rejected before operational messages are accepted. */
    @Test(expected = JSONException.class)
    public void mismatchedHelloProtocolIsRejected() throws Exception {
        DirectWebSocketTransport.parseInboundMessage(
                "{\"type\":\"hello\",\"timestamp\":1,\"payload\":{"
                        + "\"accepted\":true,\"protocolVersion\":2}}");
    }

    /** Unknown text commands are classified for a policy-close response. */
    @Test
    public void unknownCommandIsNotRouted() throws Exception {
        DirectWebSocketTransport.InboundCommand command =
                DirectWebSocketTransport.parseInboundMessage(
                        "{\"type\":\"camera.capture\",\"timestamp\":1,\"payload\":{}}");

        assertEquals(DirectWebSocketTransport.InboundType.UNSUPPORTED, command.mType);
    }

    /** Missing command type is malformed rather than treated as a legacy command. */
    @Test(expected = JSONException.class)
    public void missingTypeIsRejected() throws Exception {
        DirectWebSocketTransport.parseInboundMessage(
                "{\"requestId\":\"request-8\",\"timestamp\":1,\"payload\":{}}");
    }

    /** Non-string request IDs are rejected instead of being silently coerced. */
    @Test(expected = JSONException.class)
    public void nonStringRequestIdIsRejected() throws Exception {
        DirectWebSocketTransport.parseInboundMessage(
                "{\"type\":\"ping\",\"requestId\":8,\"timestamp\":1,\"payload\":{}}");
    }

    /** Empty request IDs are not accepted as usable correlation identifiers. */
    @Test(expected = JSONException.class)
    public void emptyRequestIdIsRejected() throws Exception {
        DirectWebSocketTransport.parseInboundMessage(
                "{\"type\":\"ping\",\"requestId\":\"\",\"timestamp\":1,\"payload\":{}}");
    }

    /** Oversized request IDs are rejected before they can be reflected in a response. */
    @Test(expected = JSONException.class)
    public void oversizedRequestIdIsRejected() throws Exception {
        String requestId = "x".repeat(AsgConstants.DIRECT_SERVER_MAX_REQUEST_ID_CHARS + 1);
        DirectWebSocketTransport.parseInboundMessage(
                "{\"type\":\"ping\",\"requestId\":\""
                        + requestId
                        + "\",\"timestamp\":1,\"payload\":{}}");
    }

    /** Zero jitter produces the documented capped exponential sequence and reset behavior. */
    @Test
    public void reconnectBackoffIsCappedAndResettable() {
        DirectWebSocketTransport.ReconnectBackoff backoff =
                new DirectWebSocketTransport.ReconnectBackoff(() -> 0.5d);

        assertEquals(1_000L, backoff.nextDelayMs());
        assertEquals(2_000L, backoff.nextDelayMs());
        assertEquals(4_000L, backoff.nextDelayMs());
        assertEquals(8_000L, backoff.nextDelayMs());
        assertEquals(16_000L, backoff.nextDelayMs());
        assertEquals(30_000L, backoff.nextDelayMs());
        assertEquals(30_000L, backoff.nextDelayMs());

        backoff.reset();

        assertEquals(1_000L, backoff.nextDelayMs());
    }

    /** Reconnect jitter stays positive and never exceeds the configured cap. */
    @Test
    public void reconnectJitterRemainsBounded() {
        DirectWebSocketTransport.ReconnectBackoff low =
                new DirectWebSocketTransport.ReconnectBackoff(() -> 0.0d);
        DirectWebSocketTransport.ReconnectBackoff high =
                new DirectWebSocketTransport.ReconnectBackoff(() -> 1.0d);

        assertEquals(800L, low.nextDelayMs());
        assertEquals(1_200L, high.nextDelayMs());
        for (int index = 0; index < 10; index++) {
            assertTrue(low.nextDelayMs() > 0L);
            assertTrue(high.nextDelayMs() <= AsgConstants.DIRECT_SERVER_RECONNECT_MAX_DELAY_MS);
        }
    }

    /** A bounded burst is accepted and the next message is rejected until the window advances. */
    @Test
    public void inboundRateLimiterCapsBursts() {
        DirectWebSocketTransport.InboundRateLimiter limiter =
                new DirectWebSocketTransport.InboundRateLimiter();

        for (int index = 0; index < AsgConstants.DIRECT_SERVER_MAX_MESSAGES_PER_WINDOW; index++) {
            assertTrue(limiter.tryAcquire(5_000L));
        }
        assertFalse(limiter.tryAcquire(5_000L));
        assertTrue(limiter.tryAcquire(5_000L + AsgConstants.DIRECT_SERVER_MESSAGE_RATE_WINDOW_MS));
    }
}
