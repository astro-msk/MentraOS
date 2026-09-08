package com.mentra.asg_client;

public class AsgConstants {
    public static final int DIRECT_VOICE_MAX_SECONDS = 12;
    public static final int DIRECT_VOICE_SILENCE_MS = 1200;
    public static final int DIRECT_VOICE_INITIAL_SILENCE_MS = 4000;
    public static final int DIRECT_VOICE_RMS_THRESHOLD = 500;
    public static final int DIRECT_VOICE_MEDIA_MAX_BYTES = 512 * 1024;
    public static final int DIRECT_TEST_MEDIA_MAX_BYTES = 1024 * 1024;
    public static final String DIRECT_AGENT_PHOTO_SIZE = "high";
    public static final int DIRECT_TEST_AUDIO_RATE = 16000;
    public static final int DIRECT_TEST_AUDIO_SECONDS = 2;
    public static final int DIRECT_TEST_LED_DURATION_MS = 500;
    public static final int DIRECT_TEST_LED_BRIGHTNESS = 64;
    public static final int DIRECT_SERVER_PROTOCOL_VERSION = 1;
    public static final long DIRECT_SERVER_HEARTBEAT_INTERVAL_MS = 15_000L;
    public static final long DIRECT_SERVER_PING_INTERVAL_MS = 20_000L;
    public static final long DIRECT_SERVER_CONNECT_TIMEOUT_MS = 10_000L;
    public static final long DIRECT_SERVER_HELLO_ACK_TIMEOUT_MS = 10_000L;
    public static final long DIRECT_SERVER_RECONNECT_INITIAL_DELAY_MS = 1_000L;
    public static final long DIRECT_SERVER_RECONNECT_MAX_DELAY_MS = 30_000L;
    public static final double DIRECT_SERVER_RECONNECT_JITTER_FRACTION = 0.2d;
    public static final int DIRECT_SERVER_MAX_MESSAGE_BYTES = 16 * 1024;
    public static final int DIRECT_SERVER_MAX_REQUEST_ID_CHARS = 128;
    public static final int DIRECT_SERVER_MAX_DEVICE_ID_CHARS = 128;
    public static final int DIRECT_SERVER_MAX_GENERAL_TEXT_CHARS = 128;
    public static final int DIRECT_SERVER_MAX_SHORT_TEXT_CHARS = 64;
    public static final int DIRECT_SERVER_MAX_MESSAGES_PER_WINDOW = 300;
    public static final long DIRECT_SERVER_MESSAGE_RATE_WINDOW_MS = 10_000L;
    public static final String DIRECT_SERVER_DEVICE_TOKEN_HEADER = "X-Cally-Device-Token";
    public static final String DIRECT_SERVER_CF_ACCESS_CLIENT_ID_HEADER = "CF-Access-Client-Id";
    public static final String DIRECT_SERVER_CF_ACCESS_CLIENT_SECRET_HEADER = "CF-Access-Client-Secret";
    public static final String DIRECT_SERVER_PREFERENCES = "cally_direct_server";
    public static final String DIRECT_SERVER_INSTALL_ID_KEY = "install_id";
    public static final int DIRECT_SERVER_UNKNOWN_WIFI_RSSI_DBM = -127;
    public static final float AUDIO_PLAYBACK_VOLUME = 0.1f;
    public static String appName = "AugmentOS ASG Client";
    public static int augmentOsSdkVerion = 1;
    public static int asgServiceNotificationId = 3540;
    public static int asgPackageMonitorServiceNotificationId = 3541;
    public static String glassesCardTitle = "";
    public static String displayRequestsKey = "display_requests";
    public static String proactiveAgentResultsKey = "results_proactive_agent_insights";
    public static String explicitAgentQueriesKey = "explicit_insight_queries";
    public static String explicitAgentResultsKey = "explicit_insight_results";
    public static String wakeWordTimeKey = "wake_word_time";
    public static String entityDefinitionsKey = "entity_definitions";
    public static String languageLearningKey = "language_learning_results";
    public static String llContextConvoKey = "ll_context_convo_results";
    public static String llWordSuggestUpgradeKey = "ll_word_suggest_upgrade_results";
    public static String shouldUpdateSettingsKey = "should_update_settings";
    public static String adhdStmbAgentKey = "adhd_stmb_agent_results";
    public static String notificationFilterKey = "notification_results";
    public static String newsSummaryKey = "news_summary_results";

    //endpoints
    public static final String LLM_QUERY_ENDPOINT = "/chat";
    public static final String SEND_NOTIFICATIONS_ENDPOINT = "/send_notifications";
    public static final String DIARIZE_QUERY_ENDPOINT = "/chat_diarization";
    public static final String GEOLOCATION_STREAM_ENDPOINT = "/gps_location";
    public static final String BUTTON_EVENT_ENDPOINT = "/button_event";
    public static final String UI_POLL_ENDPOINT = "/ui_poll";
    public static final String SET_USER_SETTINGS_ENDPOINT = "/set_user_settings";
    public static final String GET_USER_SETTINGS_ENDPOINT = "/get_user_settings";
    public static final String REQUEST_APP_BY_PACKAGE_NAME_DOWNLOAD_LINK_ENDPOINT = "/request_app_by_package_name_download_link";
    
    // Battery status broadcast action
    public static final String ACTION_GLASSES_BATTERY_STATUS = "com.augmentos.otaupdater.ACTION_GLASSES_BATTERY_STATUS";
    
    // RGB LED Control Constants (Glasses BES Chipset - Remote Control via Bluetooth)
    // NOTE: These are different from the local MTK recording LED
    
    // K900 Protocol Commands for RGB LEDs
    public static final String K900_CMD_RGB_LED_ON = "cs_ledon";
    public static final String K900_CMD_RGB_LED_OFF = "cs_ledoff";
    public static final String K900_CMD_ANDROID_CONTROL_LED = "android_control_led";  // Authority handoff
    
    // RGB LED Color Indices (BES Chipset on Glasses)
    public static final int RGB_LED_RED = 0;
    public static final int RGB_LED_GREEN = 1;
    public static final int RGB_LED_BLUE = 2;
    
    // RGB LED Command Types (from phone to glasses)
    public static final String CMD_RGB_LED_CONTROL_ON = "rgb_led_control_on";
    public static final String CMD_RGB_LED_CONTROL_OFF = "rgb_led_control_off";
}
