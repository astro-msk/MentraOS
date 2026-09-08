package com.mentra.asg_client.io.direct;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;
import android.util.Base64;
import com.mentra.asg_client.AsgConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Independent, service-owned WebSocket sidecar for the first direct Cally POC.
 *
 * <p>This transport deliberately does not implement the phone-facing {@code ICompanionTransport}
 * and never feeds messages into {@code CommandProcessor}. Its allowlist is limited to hello,
 * heartbeat, ping, pong, status, and bounded peripheral tests.
 */
public final class DirectWebSocketTransport {

    private DirectPeripheralTester mPeripheralTester;
    private DirectPcmPlayer mPcmPlayer;

    private java.util.function.BooleanSupplier mPlayTestSound = () -> false;

    private final Object mLock = new Object();
    private final DirectServerConfig mConfig;
    private final DirectDeviceStatusProvider mStatusProvider;
    private final ConnectivityManager mConnectivityManager;
    private final OkHttpClient mHttpClient;
    private final ScheduledExecutorService mScheduler;
    private final ReconnectBackoff mReconnectBackoff;
    private final InboundRateLimiter mInboundRateLimiter;
    private final ConnectivityManager.NetworkCallback mNetworkCallback;

    private boolean mStarted;
    private boolean mStopped;
    private boolean mNetworkCallbackRegistered;
    private boolean mWifiReady;
    private boolean mConnecting;
    private boolean mSocketOpen;
    private boolean mProtocolReady;
    private long mConnectionGeneration;
    private WebSocket mWebSocket;
    private ScheduledFuture<?> mReconnectFuture;
    private ScheduledFuture<?> mHelloAckFuture;
    private ScheduledFuture<?> mHeartbeatFuture;

    /** Create a direct transport with its own bounded scheduler and OkHttp connection pool. */
    public DirectWebSocketTransport(
            Context context, DirectServerConfig config, DirectDeviceStatusProvider statusProvider) {
        mConfig = config;
        mStatusProvider = statusProvider;
        mConnectivityManager =
                (ConnectivityManager)
                        context.getApplicationContext()
                                .getSystemService(Context.CONNECTIVITY_SERVICE);
        mScheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "cally-direct-scheduler");
                            thread.setDaemon(true);
                            return thread;
                        });
        mHttpClient =
                new OkHttpClient.Builder()
                        .connectTimeout(
                                AsgConstants.DIRECT_SERVER_CONNECT_TIMEOUT_MS,
                                TimeUnit.MILLISECONDS)
                        .pingInterval(
                                AsgConstants.DIRECT_SERVER_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(true)
                        .build();
        mReconnectBackoff = new ReconnectBackoff(() -> ThreadLocalRandom.current().nextDouble());
        mInboundRateLimiter = new InboundRateLimiter();
        mNetworkCallback = createNetworkCallback();
    }

    /** Inject the device adapter; transport remains independent of MCU APIs. */
    public void setPeripheralTester(DirectPeripheralTester tester) {
        mPeripheralTester = tester;
    }

    /** Inject the direct I2S PCM sink used for streamed Cally speech. */
    public void setPcmPlayer(DirectPcmPlayer player) {
        mPcmPlayer = player;
    }

    /** Inject the existing hardware playback path for a fixed diagnostic sound. */
    public void setTestSoundPlayer(java.util.function.BooleanSupplier player) {
        mPlayTestSound = player;
    }

    /**
     * Start Wi-Fi monitoring and connect when the active network uses Wi-Fi.
     *
     * <p>Calls are idempotent. A disabled or invalid build configuration leaves the existing
     * phone/BES transport running and performs no network work.
     */
    public void start() {
        synchronized (mLock) {
            if (mStarted || mStopped) {
                return;
            }
            mStarted = true;

            if (!mConfig.isValid()) {
                Log.e("DirectWebSocket", "Direct Cally disabled: " + mConfig.getValidationError());
                return;
            }
            if (!mConfig.isEnabled()) {
                Log.i("DirectWebSocket", "Direct Cally sidecar is disabled");
                return;
            }
            if (mConnectivityManager == null) {
                Log.e("DirectWebSocket", "Direct Cally unavailable: ConnectivityManager missing");
                return;
            }

            try {
                mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback);
                mNetworkCallbackRegistered = true;
            } catch (RuntimeException e) {
                Log.e("DirectWebSocket", "Unable to register direct Cally network callback", e);
            }

            refreshWifiStateLocked();
        }
    }

    /**
     * Permanently stop this sidecar instance and release callbacks, timers, socket, and HTTP pools.
     * Calls are idempotent.
     */
    public void stop() {
        WebSocket socketToClose;
        boolean socketWasConnected;
        boolean unregisterNetworkCallback;
        synchronized (mLock) {
            if (mStopped) {
                return;
            }
            mStopped = true;
            mStarted = false;
            mWifiReady = false;
            mConnectionGeneration++;
            cancelReconnectLocked();
            cancelHelloAckLocked();
            cancelHeartbeatLocked();

            socketToClose = mWebSocket;
            socketWasConnected = mSocketOpen;
            mWebSocket = null;
            mConnecting = false;
            mSocketOpen = false;
            mProtocolReady = false;

            unregisterNetworkCallback = mNetworkCallbackRegistered;
            mNetworkCallbackRegistered = false;
            mInboundRateLimiter.reset();
        }

        if (unregisterNetworkCallback && mConnectivityManager != null) {
            try {
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            } catch (RuntimeException e) {
                Log.w("DirectWebSocket", "Unable to unregister direct Cally network callback", e);
            }
        }

        if (socketToClose != null) {
            if (socketWasConnected) {
                socketToClose.close(1000, "service stopping");
            } else {
                socketToClose.cancel();
            }
        }
        mHttpClient.dispatcher().cancelAll();
        mHttpClient.connectionPool().evictAll();
        mHttpClient.dispatcher().executorService().shutdown();
        mScheduler.shutdownNow();
        Log.i("DirectWebSocket", "Direct Cally sidecar stopped");
    }

    /** Return true only after Cally has accepted the application-protocol hello. */
    public boolean isConnected() {
        synchronized (mLock) {
            return mProtocolReady;
        }
    }

    /** Return whether this lifecycle instance has received a successful start call. */
    public boolean isStarted() {
        synchronized (mLock) {
            return mStarted;
        }
    }

    private ConnectivityManager.NetworkCallback createNetworkCallback() {
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                onNetworkChanged();
            }

            @Override
            public void onCapabilitiesChanged(
                    Network network, NetworkCapabilities networkCapabilities) {
                onNetworkChanged();
            }

            @Override
            public void onLost(Network network) {
                onNetworkChanged();
            }

            @Override
            public void onUnavailable() {
                onNetworkChanged();
            }
        };
    }

    private void onNetworkChanged() {
        synchronized (mLock) {
            if (!mStarted || mStopped || !mConfig.isEnabled() || !mConfig.isValid()) {
                return;
            }
            refreshWifiStateLocked();
        }
    }

    private void refreshWifiStateLocked() {
        boolean wifiReady = hasActiveWifiNetwork();
        if (mWifiReady == wifiReady) {
            if (wifiReady) {
                ensureConnectionLocked();
            }
            return;
        }

        mWifiReady = wifiReady;
        if (wifiReady) {
            Log.i("DirectWebSocket", "Active Wi-Fi available for direct Cally");
            ensureConnectionLocked();
        } else {
            Log.i("DirectWebSocket", "Active Wi-Fi unavailable for direct Cally");
            disconnectForNetworkLossLocked();
        }
    }

    private boolean hasActiveWifiNetwork() {
        if (mConnectivityManager == null) {
            return false;
        }
        try {
            Network activeNetwork = mConnectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }
            NetworkCapabilities capabilities =
                    mConnectivityManager.getNetworkCapabilities(activeNetwork);
            // Do not require NET_CAPABILITY_VALIDATED: a LAN-only Cally endpoint may still be
            // reachable. Successful authenticated WebSocket open is the direct-route proof.
            return capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (RuntimeException e) {
            Log.w("DirectWebSocket", "Unable to inspect the active network", e);
            return false;
        }
    }

    private void ensureConnectionLocked() {
        if (!mStarted
                || mStopped
                || !mWifiReady
                || mSocketOpen
                || mConnecting
                || isReconnectScheduledLocked()) {
            return;
        }
        connectLocked();
    }

    private void connectLocked() {
        long generation = ++mConnectionGeneration;
        mConnecting = true;
        try {
            Request request = buildWebSocketRequest(mConfig);
            mWebSocket = mHttpClient.newWebSocket(request, new DirectSocketListener(generation));
            Log.i("DirectWebSocket", "Connecting to configured direct Cally endpoint");
        } catch (RuntimeException e) {
            mConnecting = false;
            mWebSocket = null;
            // Request construction validates credential headers. Do not attach the exception here:
            // OkHttp header-validation messages may echo a rejected secret value.
            Log.e(
                    "DirectWebSocket",
                    "Unable to create direct Cally WebSocket ("
                            + e.getClass().getSimpleName()
                            + ")");
            scheduleReconnectLocked();
        }
    }

    static Request buildWebSocketRequest(DirectServerConfig config) {
        Request.Builder requestBuilder = new Request.Builder().url(config.getServerUrl());
        if (config.hasCloudflareAccessCredentials()) {
            requestBuilder.header(
                    AsgConstants.DIRECT_SERVER_CF_ACCESS_CLIENT_ID_HEADER,
                    config.getCloudflareAccessClientId());
            requestBuilder.header(
                    AsgConstants.DIRECT_SERVER_CF_ACCESS_CLIENT_SECRET_HEADER,
                    config.getCloudflareAccessClientSecret());
        }
        if (config.hasDeviceToken()) {
            requestBuilder.header(
                    AsgConstants.DIRECT_SERVER_DEVICE_TOKEN_HEADER, config.getDeviceToken());
        }
        return requestBuilder.build();
    }

    private void onSocketOpen(long generation, WebSocket webSocket) {
        synchronized (mLock) {
            if (!isCurrentCallbackLocked(generation, webSocket)) {
                webSocket.close(1000, "stale connection");
                return;
            }

            mWebSocket = webSocket;
            mConnecting = false;
            mSocketOpen = true;
            mProtocolReady = false;
            mInboundRateLimiter.reset();
            cancelReconnectLocked();
            Log.i("DirectWebSocket", "Direct Cally WebSocket open; awaiting hello acknowledgement");

            try {
                if (!sendEnvelopeLocked("hello", null, mStatusProvider.buildHelloPayload())) {
                    rejectCurrentSocketLocked(generation, webSocket, 1011, "hello send failed");
                    return;
                }
                scheduleHelloAckTimeoutLocked(generation);
            } catch (RuntimeException e) {
                Log.e("DirectWebSocket", "Unable to build direct Cally hello", e);
                rejectCurrentSocketLocked(generation, webSocket, 1011, "hello unavailable");
            }
        }
    }

    private void onTextMessage(long generation, WebSocket webSocket, String text) {
        int messageBytes = text.getBytes(StandardCharsets.UTF_8).length;
        synchronized (mLock) {
            if (!isCurrentCallbackLocked(generation, webSocket)) {
                return;
            }
            if (messageBytes > AsgConstants.DIRECT_SERVER_MAX_MESSAGE_BYTES) {
                rejectCurrentSocketLocked(generation, webSocket, 1009, "message too large");
                return;
            }
            if (!mInboundRateLimiter.tryAcquire(SystemClock.elapsedRealtime())) {
                rejectCurrentSocketLocked(generation, webSocket, 1008, "message rate exceeded");
                return;
            }

            try {
                InboundCommand command = parseInboundMessage(text);
                if (!mProtocolReady && !isAllowedBeforeProtocolReady(command.mType)) {
                    rejectCurrentSocketLocked(
                            generation, webSocket, 1008, "hello acknowledgement required");
                    return;
                }
                switch (command.mType) {
                    case HELLO_ACK:
                        if (mProtocolReady) {
                            rejectCurrentSocketLocked(
                                    generation, webSocket, 1008, "duplicate hello acknowledgement");
                            return;
                        }
                        mProtocolReady = true;
                        mReconnectBackoff.reset();
                        cancelHelloAckLocked();
                        Log.i("DirectWebSocket", "Direct Cally hello acknowledged");
                        if (!sendEnvelopeLocked(
                                        "status", null, mStatusProvider.buildStatusPayload())
                                || !sendEnvelopeLocked(
                                        "heartbeat", null, mStatusProvider.buildStatusPayload())) {
                            rejectCurrentSocketLocked(
                                    generation, webSocket, 1011, "initial status send failed");
                            return;
                        }
                        scheduleHeartbeatLocked(generation);
                        break;
                    case PING:
                        if (!sendEnvelopeLocked("pong", command.mRequestId, new JSONObject())) {
                            rejectCurrentSocketLocked(
                                    generation, webSocket, 1011, "pong send failed");
                        }
                        break;
                    case TEST_SOUND:
                        boolean accepted = mPlayTestSound.getAsBoolean();
                        sendEnvelopeLocked("sound_result", command.mRequestId,
                                new JSONObject().put("accepted", accepted));
                        break;
                    case SPEECH_BEGIN:
                        boolean began = mPcmPlayer != null && mPcmPlayer.begin(
                                command.mPayload.getString("streamId"),
                                command.mPayload.getInt("sampleRate"));
                        sendEnvelopeLocked("speech_result", command.mRequestId,
                                new JSONObject().put("accepted", began).put("phase", "begin"));
                        break;
                    case SPEECH_CHUNK:
                        if (mPcmPlayer == null || !mPcmPlayer.write(
                                command.mPayload.getString("streamId"),
                                command.mPayload.getInt("sequence"),
                                Base64.decode(command.mPayload.getString("base64"), Base64.NO_WRAP))) {
                            Log.w("DirectWebSocket", "Dropped invalid direct PCM chunk");
                        }
                        break;
                    case SPEECH_END:
                        boolean ended = mPcmPlayer != null
                                && mPcmPlayer.finish(command.mPayload.getString("streamId"));
                        sendEnvelopeLocked("speech_result", command.mRequestId,
                                new JSONObject().put("accepted", ended).put("phase", "end"));
                        break;
                    case SPEECH_ABORT:
                        if (mPcmPlayer != null) {
                            mPcmPlayer.abort(command.mPayload.getString("streamId"));
                        }
                        break;
                    case TEST_LED:
                    case TEST_PHOTO:
                    case TEST_VOICE:
                    case TEST_MIC:
                    case TEST_BUTTONS:
                        if (mPeripheralTester == null) throw new IllegalStateException("no peripheral adapter");
                        mPeripheralTester.execute(command.mType.name().substring(5).toLowerCase(Locale.ROOT),
                                result -> {
                                    synchronized (mLock) {
                                        if (isCurrentCallbackLocked(generation, webSocket) && mProtocolReady) {
                                            sendEnvelopeLocked("peripheral_result", command.mRequestId, result);
                                        }
                                    }
                                });
                        break;
                    case STATUS:
                        if (!sendEnvelopeLocked(
                                "status",
                                command.mRequestId,
                                mStatusProvider.buildStatusPayload())) {
                            rejectCurrentSocketLocked(
                                    generation, webSocket, 1011, "status send failed");
                        }
                        break;
                    case UNSUPPORTED:
                        rejectCurrentSocketLocked(
                                generation, webSocket, 1008, "unsupported message type");
                        break;
                }
            } catch (JSONException | RuntimeException e) {
                Log.w("DirectWebSocket", "Rejecting malformed direct Cally message", e);
                rejectCurrentSocketLocked(generation, webSocket, 1007, "malformed message");
            }
        }
    }

    private void onBinaryMessage(long generation, WebSocket webSocket, ByteString bytes) {
        synchronized (mLock) {
            if (!isCurrentCallbackLocked(generation, webSocket)) {
                return;
            }
            int closeCode =
                    bytes.size() > AsgConstants.DIRECT_SERVER_MAX_MESSAGE_BYTES ? 1009 : 1003;
            String reason = closeCode == 1009 ? "message too large" : "binary unsupported";
            rejectCurrentSocketLocked(generation, webSocket, closeCode, reason);
        }
    }

    private void onSocketEnded(
            long generation, WebSocket webSocket, String event, Throwable failure) {
        synchronized (mLock) {
            if (!isCurrentCallbackLocked(generation, webSocket)) {
                return;
            }
            mConnectionGeneration++;
            mWebSocket = null;
            mConnecting = false;
            mSocketOpen = false;
            mProtocolReady = false;
            cancelHelloAckLocked();
            cancelHeartbeatLocked();
            if (failure == null) {
                Log.w("DirectWebSocket", "Direct Cally WebSocket " + event);
            } else {
                Log.w("DirectWebSocket", "Direct Cally WebSocket " + event, failure);
            }
            scheduleReconnectLocked();
        }
    }

    private boolean isCurrentCallbackLocked(long generation, WebSocket webSocket) {
        return mStarted
                && !mStopped
                && generation == mConnectionGeneration
                && (mWebSocket == null || mWebSocket == webSocket);
    }

    private boolean sendEnvelopeLocked(String type, String requestId, JSONObject payload) {
        return mWebSocket != null
                && mSocketOpen
                && mWebSocket.send(
                        buildEnvelope(type, requestId, payload, System.currentTimeMillis())
                                .toString());
    }

    private void scheduleHeartbeatLocked(long generation) {
        cancelHeartbeatLocked();
        mHeartbeatFuture =
                mScheduler.scheduleWithFixedDelay(
                        () -> {
                            synchronized (mLock) {
                                if (!mSocketOpen
                                        || !mProtocolReady
                                        || mWebSocket == null
                                        || generation != mConnectionGeneration) {
                                    return;
                                }
                                try {
                                    if (!sendEnvelopeLocked(
                                            "heartbeat",
                                            null,
                                            mStatusProvider.buildStatusPayload())) {
                                        rejectCurrentSocketLocked(
                                                generation,
                                                mWebSocket,
                                                1011,
                                                "heartbeat send failed");
                                    }
                                } catch (RuntimeException e) {
                                    Log.w(
                                            "DirectWebSocket",
                                            "Unable to build direct Cally heartbeat",
                                            e);
                                    rejectCurrentSocketLocked(
                                            generation, mWebSocket, 1011, "heartbeat unavailable");
                                }
                            }
                        },
                        mConfig.getHeartbeatIntervalMs(),
                        mConfig.getHeartbeatIntervalMs(),
                        TimeUnit.MILLISECONDS);
    }

    private void scheduleReconnectLocked() {
        if (!mStarted
                || mStopped
                || !mWifiReady
                || mSocketOpen
                || mConnecting
                || isReconnectScheduledLocked()) {
            return;
        }

        long delayMs = mReconnectBackoff.nextDelayMs();
        long expectedGeneration = mConnectionGeneration;
        Log.i(
                "DirectWebSocket",
                String.format(Locale.US, "Retrying direct Cally in %.1fs", delayMs / 1000.0d));
        mReconnectFuture =
                mScheduler.schedule(
                        () -> {
                            synchronized (mLock) {
                                mReconnectFuture = null;
                                if (expectedGeneration != mConnectionGeneration) {
                                    return;
                                }
                                ensureConnectionLocked();
                            }
                        },
                        delayMs,
                        TimeUnit.MILLISECONDS);
    }

    private boolean isReconnectScheduledLocked() {
        return mReconnectFuture != null && !mReconnectFuture.isDone();
    }

    private void rejectCurrentSocketLocked(
            long generation, WebSocket webSocket, int closeCode, String reason) {
        if (!isCurrentCallbackLocked(generation, webSocket)) {
            return;
        }
        mConnectionGeneration++;
        mWebSocket = null;
        mConnecting = false;
        mSocketOpen = false;
        mProtocolReady = false;
        cancelHelloAckLocked();
        cancelHeartbeatLocked();
        webSocket.close(closeCode, reason);
        scheduleReconnectLocked();
    }

    private void disconnectForNetworkLossLocked() {
        cancelReconnectLocked();
        cancelHelloAckLocked();
        cancelHeartbeatLocked();
        mReconnectBackoff.reset();
        mConnectionGeneration++;
        WebSocket webSocket = mWebSocket;
        boolean wasConnected = mSocketOpen;
        mWebSocket = null;
        mConnecting = false;
        mSocketOpen = false;
        mProtocolReady = false;
        if (webSocket != null) {
            if (wasConnected) {
                webSocket.close(1001, "Wi-Fi unavailable");
            } else {
                webSocket.cancel();
            }
        }
    }

    private void cancelReconnectLocked() {
        if (mReconnectFuture != null) {
            mReconnectFuture.cancel(false);
            mReconnectFuture = null;
        }
    }

    private void scheduleHelloAckTimeoutLocked(long generation) {
        cancelHelloAckLocked();
        mHelloAckFuture =
                mScheduler.schedule(
                        () -> {
                            synchronized (mLock) {
                                if (generation != mConnectionGeneration
                                        || mProtocolReady
                                        || mWebSocket == null) {
                                    return;
                                }
                                rejectCurrentSocketLocked(
                                        generation,
                                        mWebSocket,
                                        1008,
                                        "hello acknowledgement timeout");
                            }
                        },
                        AsgConstants.DIRECT_SERVER_HELLO_ACK_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
    }

    private void cancelHelloAckLocked() {
        if (mHelloAckFuture != null) {
            mHelloAckFuture.cancel(false);
            mHelloAckFuture = null;
        }
    }

    private void cancelHeartbeatLocked() {
        if (mHeartbeatFuture != null) {
            mHeartbeatFuture.cancel(false);
            mHeartbeatFuture = null;
        }
    }

    static JSONObject buildEnvelope(
            String type, String requestId, JSONObject payload, long timestamp) {
        try {
            JSONObject envelope = new JSONObject();
            envelope.put("type", type);
            if (requestId != null) {
                envelope.put("requestId", requestId);
            }
            envelope.put("timestamp", timestamp);
            envelope.put("payload", payload == null ? new JSONObject() : payload);
            return envelope;
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build direct-server envelope", e);
        }
    }

    static InboundCommand parseInboundMessage(String text) throws JSONException {
        JSONObject message = new JSONObject(text);
        Object rawType = message.opt("type");
        if (!(rawType instanceof String) || ((String) rawType).trim().isEmpty()) {
            throw new JSONException("type must be a non-empty string");
        }

        String requestId = null;
        if (message.has("requestId") && !message.isNull("requestId")) {
            Object rawRequestId = message.get("requestId");
            if (!(rawRequestId instanceof String)) {
                throw new JSONException("requestId must be a string");
            }
            requestId = (String) rawRequestId;
            if (requestId.trim().isEmpty()
                    || requestId.length() > AsgConstants.DIRECT_SERVER_MAX_REQUEST_ID_CHARS) {
                throw new JSONException("requestId length is invalid");
            }
        }

        validateCommonEnvelope(message);

        String type = ((String) rawType).trim();
        if ("hello".equals(type)) {
            validateHelloAcknowledgement(message);
            return new InboundCommand(InboundType.HELLO_ACK, requestId);
        }
        if ("ping".equals(type)) {
            validateEmptyPayload(message);
            return new InboundCommand(InboundType.PING, requestId);
        }
        if ("test_led".equals(type) || "test_photo".equals(type)
                || "test_voice".equals(type) || "test_mic".equals(type) || "test_buttons".equals(type)) {
            validateEmptyPayload(message);
            if (requestId == null) throw new JSONException("test requires requestId");
            return new InboundCommand(InboundType.valueOf(type.toUpperCase(Locale.ROOT)), requestId);
        }
        if ("test_sound".equals(type)) {
            validateEmptyPayload(message);
            return new InboundCommand(InboundType.TEST_SOUND, requestId);
        }
        if ("speech_begin".equals(type)) {
            if (requestId == null) throw new JSONException("speech begin requires requestId");
            JSONObject payload = validateSpeechPayload(message, false);
            if (payload.optInt("sampleRate", -1) != 24000
                    || payload.optInt("channels", -1) != 1
                    || payload.optInt("bitsPerSample", -1) != 16) {
                throw new JSONException("unsupported speech format");
            }
            return new InboundCommand(InboundType.SPEECH_BEGIN, requestId, payload);
        }
        if ("speech_chunk".equals(type)) {
            JSONObject payload = validateSpeechPayload(message, true);
            Object rawSequence = payload.opt("sequence");
            String encoded = payload.optString("base64", "");
            if (!(rawSequence instanceof Number) || ((Number) rawSequence).intValue() < 0
                    || encoded.isEmpty() || encoded.length() > 10924) {
                throw new JSONException("invalid speech chunk");
            }
            return new InboundCommand(InboundType.SPEECH_CHUNK, requestId, payload);
        }
        if ("speech_end".equals(type) || "speech_abort".equals(type)) {
            if ("speech_end".equals(type) && requestId == null)
                throw new JSONException("speech end requires requestId");
            JSONObject payload = validateSpeechPayload(message, false);
            return new InboundCommand("speech_end".equals(type)
                    ? InboundType.SPEECH_END : InboundType.SPEECH_ABORT, requestId, payload);
        }
        if ("status".equals(type)) {
            validateEmptyPayload(message);
            return new InboundCommand(InboundType.STATUS, requestId);
        }
        return new InboundCommand(InboundType.UNSUPPORTED, requestId);
    }

    static boolean isAllowedBeforeProtocolReady(InboundType type) {
        return type == InboundType.HELLO_ACK;
    }

    private static void validateCommonEnvelope(JSONObject message) throws JSONException {
        Object timestamp = message.opt("timestamp");
        if (!(timestamp instanceof Number)) {
            throw new JSONException("timestamp must be a non-negative integer");
        }
        double timestampValue = ((Number) timestamp).doubleValue();
        if (!Double.isFinite(timestampValue)
                || timestampValue < 0.0d
                || timestampValue != Math.rint(timestampValue)) {
            throw new JSONException("timestamp must be a non-negative integer");
        }
        if (!(message.opt("payload") instanceof JSONObject)) {
            throw new JSONException("payload must be an object");
        }
    }

    private static void validateEmptyPayload(JSONObject message) throws JSONException {
        JSONObject payload = message.getJSONObject("payload");
        if (payload.length() != 0) {
            throw new JSONException("request payload must be empty");
        }
    }

    private static JSONObject validateSpeechPayload(JSONObject message, boolean allowChunk)
            throws JSONException {
        JSONObject payload = message.getJSONObject("payload");
        String streamId = payload.optString("streamId", "");
        if (streamId.isEmpty() || streamId.length() > 64) {
            throw new JSONException("invalid speech streamId");
        }
        int expectedKeys = allowChunk ? 3 : 1;
        if (payload.has("sampleRate")) expectedKeys += 3;
        if (payload.length() != expectedKeys) throw new JSONException("invalid speech payload");
        return payload;
    }

    private static void validateHelloAcknowledgement(JSONObject message) throws JSONException {
        Object rawPayload = message.opt("payload");
        if (!(rawPayload instanceof JSONObject)) {
            throw new JSONException("hello acknowledgement payload must be an object");
        }
        JSONObject payload = (JSONObject) rawPayload;
        Object accepted = payload.opt("accepted");
        if (!(accepted instanceof Boolean) || !((Boolean) accepted)) {
            throw new JSONException("hello acknowledgement was not accepted");
        }
        Object protocolVersion = payload.opt("protocolVersion");
        if (!(protocolVersion instanceof Number)
                || ((Number) protocolVersion).intValue()
                        != AsgConstants.DIRECT_SERVER_PROTOCOL_VERSION) {
            throw new JSONException("hello acknowledgement protocolVersion mismatch");
        }
        if (payload.has("heartbeatTimeoutMs")) {
            Object heartbeatTimeoutMs = payload.opt("heartbeatTimeoutMs");
            if (!(heartbeatTimeoutMs instanceof Number)
                    || ((Number) heartbeatTimeoutMs).longValue() <= 0L) {
                throw new JSONException("hello acknowledgement heartbeatTimeoutMs is invalid");
            }
        }
    }

    private final class DirectSocketListener extends WebSocketListener {
        private final long mGeneration;

        DirectSocketListener(long generation) {
            mGeneration = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            onSocketOpen(mGeneration, webSocket);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            onTextMessage(mGeneration, webSocket, text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            onBinaryMessage(mGeneration, webSocket, bytes);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
            onSocketEnded(mGeneration, webSocket, "closing", null);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            onSocketEnded(mGeneration, webSocket, "closed", null);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
            onSocketEnded(mGeneration, webSocket, "failed", throwable);
        }
    }

    enum InboundType {
        HELLO_ACK,
        PING,
        TEST_SOUND,
        SPEECH_BEGIN,
        SPEECH_CHUNK,
        SPEECH_END,
        SPEECH_ABORT,
        TEST_LED,
        TEST_PHOTO,
        TEST_VOICE,
        TEST_MIC,
        TEST_BUTTONS,
        STATUS,
        UNSUPPORTED
    }

    static final class InboundCommand {
        final InboundType mType;
        final String mRequestId;
        final JSONObject mPayload;

        InboundCommand(InboundType type, String requestId) {
            this(type, requestId, new JSONObject());
        }

        InboundCommand(InboundType type, String requestId, JSONObject payload) {
            mType = type;
            mRequestId = requestId;
            mPayload = payload;
        }
    }

    interface JitterSource {
        double nextUnit();
    }

    static final class ReconnectBackoff {
        private final JitterSource mJitterSource;
        private int mAttempt;

        ReconnectBackoff(JitterSource jitterSource) {
            mJitterSource = jitterSource;
        }

        long nextDelayMs() {
            int shift = Math.min(mAttempt, 30);
            long initial = AsgConstants.DIRECT_SERVER_RECONNECT_INITIAL_DELAY_MS;
            long maximum = AsgConstants.DIRECT_SERVER_RECONNECT_MAX_DELAY_MS;
            long baseDelay;
            if (shift >= 63 || initial > (Long.MAX_VALUE >> shift)) {
                baseDelay = maximum;
            } else {
                baseDelay = Math.min(maximum, initial << shift);
            }
            mAttempt++;

            double unit = Math.max(0.0d, Math.min(1.0d, mJitterSource.nextUnit()));
            double signedJitter =
                    ((unit * 2.0d) - 1.0d) * AsgConstants.DIRECT_SERVER_RECONNECT_JITTER_FRACTION;
            long jittered = Math.round(baseDelay * (1.0d + signedJitter));
            return Math.max(1L, Math.min(maximum, jittered));
        }

        void reset() {
            mAttempt = 0;
        }
    }

    static final class InboundRateLimiter {
        private final ArrayDeque<Long> mAcceptedAtMs = new ArrayDeque<>();

        boolean tryAcquire(long nowMs) {
            long cutoff = nowMs - AsgConstants.DIRECT_SERVER_MESSAGE_RATE_WINDOW_MS;
            while (!mAcceptedAtMs.isEmpty() && mAcceptedAtMs.peekFirst() <= cutoff) {
                mAcceptedAtMs.removeFirst();
            }
            if (mAcceptedAtMs.size() >= AsgConstants.DIRECT_SERVER_MAX_MESSAGES_PER_WINDOW) {
                return false;
            }
            mAcceptedAtMs.addLast(nowMs);
            return true;
        }

        void reset() {
            mAcceptedAtMs.clear();
        }
    }
}
