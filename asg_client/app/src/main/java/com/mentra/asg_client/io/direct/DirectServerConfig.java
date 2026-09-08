package com.mentra.asg_client.io.direct;

import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.BuildConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Immutable, validated build-time configuration for the direct Cally WebSocket POC. */
public final class DirectServerConfig {

    /** Whether the independent direct-server sidecar should run. */
    public enum Mode {
        DISABLED,
        ENABLED
    }

    private final Mode mMode;
    private final String mServerUrl;
    private final String mDeviceToken;
    private final String mCloudflareAccessClientId;
    private final String mCloudflareAccessClientSecret;
    private final boolean mAllowCleartextDebug;
    private final boolean mDebugBuild;
    private final String mValidationError;

    private DirectServerConfig(
            Mode mode,
            String serverUrl,
            String deviceToken,
            String cloudflareAccessClientId,
            String cloudflareAccessClientSecret,
            boolean allowCleartextDebug,
            boolean debugBuild,
            String validationError) {
        mMode = mode;
        mServerUrl = serverUrl;
        mDeviceToken = deviceToken;
        mCloudflareAccessClientId = cloudflareAccessClientId;
        mCloudflareAccessClientSecret = cloudflareAccessClientSecret;
        mAllowCleartextDebug = allowCleartextDebug;
        mDebugBuild = debugBuild;
        mValidationError = validationError;
    }

    /** Create direct-server configuration from generated {@link BuildConfig} values. */
    public static DirectServerConfig fromBuildConfig() {
        return create(
                BuildConfig.CALLY_DIRECT_MODE,
                BuildConfig.CALLY_DIRECT_URL,
                BuildConfig.CALLY_DIRECT_DEVICE_TOKEN,
                BuildConfig.CALLY_DIRECT_CF_ACCESS_CLIENT_ID,
                BuildConfig.CALLY_DIRECT_CF_ACCESS_CLIENT_SECRET,
                BuildConfig.CALLY_DIRECT_ALLOW_CLEARTEXT_DEBUG,
                BuildConfig.DEBUG);
    }

    /**
     * Create and validate an explicit configuration.
     *
     * <p>This factory is also the deterministic unit-test seam. Cleartext {@code ws://} is accepted
     * only when both {@code debugBuild} and {@code allowCleartextDebug} are true.
     */
    public static DirectServerConfig create(
            String mode,
            String serverUrl,
            String deviceToken,
            String cloudflareAccessClientId,
            String cloudflareAccessClientSecret,
            boolean allowCleartextDebug,
            boolean debugBuild) {
        String normalizedMode = normalize(mode).toLowerCase(Locale.US);
        String normalizedUrl = normalize(serverUrl);
        String normalizedToken = normalize(deviceToken);
        String normalizedCloudflareAccessClientId = normalize(cloudflareAccessClientId);
        String normalizedCloudflareAccessClientSecret = normalize(cloudflareAccessClientSecret);

        boolean hasCloudflareAccessClientId = !normalizedCloudflareAccessClientId.isEmpty();
        boolean hasCloudflareAccessClientSecret = !normalizedCloudflareAccessClientSecret.isEmpty();
        if (hasCloudflareAccessClientId != hasCloudflareAccessClientSecret) {
            return invalid(
                    normalizedUrl,
                    normalizedToken,
                    normalizedCloudflareAccessClientId,
                    normalizedCloudflareAccessClientSecret,
                    allowCleartextDebug,
                    debugBuild,
                    "CALLY_DIRECT_CF_ACCESS_CLIENT_ID and "
                            + "CALLY_DIRECT_CF_ACCESS_CLIENT_SECRET must be configured together");
        }

        if (normalizedMode.isEmpty()
                || "disabled".equals(normalizedMode)
                || "off".equals(normalizedMode)) {
            return new DirectServerConfig(
                    Mode.DISABLED,
                    normalizedUrl,
                    normalizedToken,
                    normalizedCloudflareAccessClientId,
                    normalizedCloudflareAccessClientSecret,
                    allowCleartextDebug,
                    debugBuild,
                    "");
        }

        if (!"enabled".equals(normalizedMode) && !"on".equals(normalizedMode)) {
            return invalid(
                    normalizedUrl,
                    normalizedToken,
                    normalizedCloudflareAccessClientId,
                    normalizedCloudflareAccessClientSecret,
                    allowCleartextDebug,
                    debugBuild,
                    "CALLY_DIRECT_MODE must be 'enabled' or 'disabled'");
        }

        if (normalizedUrl.isEmpty()) {
            return invalid(
                    normalizedUrl,
                    normalizedToken,
                    normalizedCloudflareAccessClientId,
                    normalizedCloudflareAccessClientSecret,
                    allowCleartextDebug,
                    debugBuild,
                    "CALLY_DIRECT_URL is required when direct mode is enabled");
        }

        if (normalizedToken.isEmpty() && !hasCloudflareAccessClientId) {
            return invalid(
                    normalizedUrl,
                    normalizedToken,
                    normalizedCloudflareAccessClientId,
                    normalizedCloudflareAccessClientSecret,
                    allowCleartextDebug,
                    debugBuild,
                    "Direct mode requires a Cloudflare Access service-token pair or the legacy "
                            + "CALLY_DIRECT_DEVICE_TOKEN development credential");
        }

        String urlError = validateUrl(normalizedUrl, allowCleartextDebug, debugBuild);
        if (!urlError.isEmpty()) {
            return invalid(
                    normalizedUrl,
                    normalizedToken,
                    normalizedCloudflareAccessClientId,
                    normalizedCloudflareAccessClientSecret,
                    allowCleartextDebug,
                    debugBuild,
                    urlError);
        }

        return new DirectServerConfig(
                Mode.ENABLED,
                normalizedUrl,
                normalizedToken,
                normalizedCloudflareAccessClientId,
                normalizedCloudflareAccessClientSecret,
                allowCleartextDebug,
                debugBuild,
                "");
    }

    /** Return the configured sidecar mode. */
    public Mode getMode() {
        return mMode;
    }

    /** Return true when the direct sidecar is explicitly enabled. */
    public boolean isEnabled() {
        return mMode == Mode.ENABLED;
    }

    /** Return true when all settings are safe and internally consistent. */
    public boolean isValid() {
        return mValidationError.isEmpty();
    }

    /** Return a non-secret validation error, or an empty string when valid. */
    public String getValidationError() {
        return mValidationError;
    }

    /** Return the configured WebSocket URL. */
    public String getServerUrl() {
        return mServerUrl;
    }

    /**
     * Return the temporary first-POC device token.
     *
     * <p>Callers must never log this value. Production device identity should replace the embedded
     * build-time token.
     */
    public String getDeviceToken() {
        return mDeviceToken;
    }

    /** Return true when the temporary legacy development credential is configured. */
    public boolean hasDeviceToken() {
        return !mDeviceToken.isEmpty();
    }

    /** Return true when a complete Cloudflare Access service-token pair is configured. */
    public boolean hasCloudflareAccessCredentials() {
        return !mCloudflareAccessClientId.isEmpty() && !mCloudflareAccessClientSecret.isEmpty();
    }

    /**
     * Return the Cloudflare Access service-token client identifier.
     *
     * <p>Callers must never log this value.
     */
    public String getCloudflareAccessClientId() {
        return mCloudflareAccessClientId;
    }

    /**
     * Return the Cloudflare Access service-token client secret.
     *
     * <p>Callers must never log this value.
     */
    public String getCloudflareAccessClientSecret() {
        return mCloudflareAccessClientSecret;
    }

    /** Return whether cleartext was explicitly requested for a local debug build. */
    public boolean isCleartextDebugAllowed() {
        return mAllowCleartextDebug && mDebugBuild;
    }

    /** Return the direct application protocol version. */
    public int getProtocolVersion() {
        return AsgConstants.DIRECT_SERVER_PROTOCOL_VERSION;
    }

    /** Return the application heartbeat interval in milliseconds. */
    public long getHeartbeatIntervalMs() {
        return AsgConstants.DIRECT_SERVER_HEARTBEAT_INTERVAL_MS;
    }

    private static DirectServerConfig invalid(
            String serverUrl,
            String deviceToken,
            String cloudflareAccessClientId,
            String cloudflareAccessClientSecret,
            boolean allowCleartextDebug,
            boolean debugBuild,
            String validationError) {
        return new DirectServerConfig(
                Mode.DISABLED,
                serverUrl,
                deviceToken,
                cloudflareAccessClientId,
                cloudflareAccessClientSecret,
                allowCleartextDebug,
                debugBuild,
                validationError);
    }

    private static String validateUrl(
            String serverUrl, boolean allowCleartextDebug, boolean debugBuild) {
        try {
            URI uri = new URI(serverUrl);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getHost().isEmpty()) {
                return "CALLY_DIRECT_URL must be an absolute WebSocket URL with a host";
            }
            if (uri.getUserInfo() != null) {
                return "CALLY_DIRECT_URL must not contain credentials";
            }
            if (uri.getRawQuery() != null) {
                return "CALLY_DIRECT_URL must not contain query parameters";
            }
            if (uri.getFragment() != null) {
                return "CALLY_DIRECT_URL must not contain a fragment";
            }

            String normalizedScheme = scheme.toLowerCase(Locale.US);
            if ("wss".equals(normalizedScheme)) {
                return "";
            }
            if ("ws".equals(normalizedScheme)) {
                return debugBuild && allowCleartextDebug
                        ? ""
                        : "Cleartext CALLY_DIRECT_URL requires a debug build and explicit opt-in";
            }
            return "CALLY_DIRECT_URL must use wss:// (or explicitly enabled debug ws://)";
        } catch (URISyntaxException e) {
            return "CALLY_DIRECT_URL is not a valid URI";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
