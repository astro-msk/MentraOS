package com.mentra.asg_client.io.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for direct-server build configuration validation. */
public class DirectServerConfigTest {

    /** Disabled mode remains valid without an endpoint or credential. */
    @Test
    public void disabledModeAllowsEmptySettings() {
        DirectServerConfig config =
                DirectServerConfig.create("disabled", "", "", "", "", false, false);

        assertTrue(config.isValid());
        assertFalse(config.isEnabled());
        assertEquals(DirectServerConfig.Mode.DISABLED, config.getMode());
    }

    /** Enabled production configuration accepts authenticated TLS WebSockets. */
    @Test
    public void enabledModeAcceptsWss() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect",
                        "temporary-test-token",
                        "",
                        "",
                        false,
                        false);

        assertTrue(config.isValid());
        assertTrue(config.isEnabled());
        assertEquals(DirectServerConfig.Mode.ENABLED, config.getMode());
    }

    /** Enabled mode rejects missing device authentication. */
    @Test
    public void enabledModeRequiresAuthentication() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect",
                        "",
                        "",
                        "",
                        false,
                        true);

        assertFalse(config.isValid());
        assertFalse(config.isEnabled());
        assertTrue(config.getValidationError().contains("Cloudflare Access"));
    }

    /** A complete Cloudflare service-token pair works without the legacy development token. */
    @Test
    public void enabledModeAcceptsCloudflareAccessPair() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect",
                        "",
                        "service-token-id.access",
                        "service-token-secret",
                        false,
                        false);

        assertTrue(config.isValid());
        assertTrue(config.isEnabled());
        assertFalse(config.hasDeviceToken());
        assertTrue(config.hasCloudflareAccessCredentials());
    }

    /** Cloudflare credentials are rejected unless both halves of the pair are present. */
    @Test
    public void cloudflareAccessPairIsAllOrNothing() {
        DirectServerConfig missingSecret =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect",
                        "temporary-test-token",
                        "service-token-id.access",
                        "",
                        false,
                        false);
        DirectServerConfig missingId =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect",
                        "temporary-test-token",
                        "",
                        "service-token-secret",
                        false,
                        false);

        assertFalse(missingSecret.isValid());
        assertFalse(missingId.isValid());
        assertTrue(missingSecret.getValidationError().contains("configured together"));
        assertTrue(missingId.getValidationError().contains("configured together"));
    }

    /** Release builds reject cleartext even when the environment flag is set. */
    @Test
    public void releaseBuildAlwaysRejectsCleartext() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "ws://192.168.1.20:9090/v1/glasses/connect",
                        "temporary-test-token",
                        "",
                        "",
                        true,
                        false);

        assertFalse(config.isValid());
        assertFalse(config.isEnabled());
        assertTrue(config.getValidationError().contains("Cleartext"));
    }

    /** Local debug builds require an explicit second opt-in before accepting cleartext. */
    @Test
    public void debugBuildAcceptsExplicitCleartextOptIn() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "ws://192.168.1.20:9090/v1/glasses/connect",
                        "temporary-test-token",
                        "",
                        "",
                        true,
                        true);

        assertTrue(config.isValid());
        assertTrue(config.isEnabled());
        assertTrue(config.isCleartextDebugAllowed());
    }

    /** Unknown modes fail closed instead of silently enabling direct access. */
    @Test
    public void unknownModeFailsClosed() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "sometimes",
                        "wss://cally.example/v1/glasses/connect",
                        "temporary-test-token",
                        "",
                        "",
                        false,
                        true);

        assertFalse(config.isValid());
        assertFalse(config.isEnabled());
        assertTrue(config.getValidationError().contains("MODE"));
    }

    /** Endpoint credentials are rejected so authentication cannot leak through URL logging. */
    @Test
    public void endpointUserInfoIsRejected() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "wss://user:password@cally.example/v1/glasses/connect",
                        "temporary-test-token",
                        "",
                        "",
                        false,
                        true);

        assertFalse(config.isValid());
        assertTrue(config.getValidationError().contains("credentials"));
    }

    /** Endpoint query strings are rejected so credentials cannot leak through URL logging. */
    @Test
    public void endpointQueryIsRejected() {
        DirectServerConfig config =
                DirectServerConfig.create(
                        "enabled",
                        "wss://cally.example/v1/glasses/connect?token=do-not-log",
                        "temporary-test-token",
                        "",
                        "",
                        false,
                        true);

        assertFalse(config.isValid());
        assertTrue(config.getValidationError().contains("query"));
    }
}
