package com.mentra.asg_client.io.direct;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.network.interfaces.INetworkManager;
import com.mentra.asg_client.service.system.interfaces.IStateManager;
import com.mentra.asg_client.service.utils.ProcessSessionId;
import com.mentra.asg_client.service.utils.ServiceUtils;
import com.mentra.asg_client.service.utils.SysProp;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds nonblocking identity and status snapshots for the direct Cally control-plane POC. */
public final class DirectDeviceStatusProvider {

    private final Context mContext;
    private final IStateManager mStateManager;
    private final INetworkManager mNetworkManager;
    private final String mDeviceId;

    /** Create a status provider backed by the existing cached state and network abstractions. */
    public DirectDeviceStatusProvider(
            Context context, IStateManager stateManager, INetworkManager networkManager) {
        mContext = context.getApplicationContext();
        mStateManager = stateManager;
        mNetworkManager = networkManager;
        mDeviceId = resolveDeviceId(mContext);
    }

    /** Build the device identity payload sent in every new socket's {@code hello}. */
    public JSONObject buildHelloPayload() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("protocolVersion", AsgConstants.DIRECT_SERVER_PROTOCOL_VERSION);
            payload.put("deviceId", mDeviceId);
            payload.put("processSid", ProcessSessionId.SID);
            payload.put(
                    "deviceModel",
                    truncate(
                            ServiceUtils.getDeviceTypeString(mContext),
                            AsgConstants.DIRECT_SERVER_MAX_GENERAL_TEXT_CHARS));
            payload.put(
                    "androidVersion",
                    truncate(
                            Build.VERSION.RELEASE,
                            AsgConstants.DIRECT_SERVER_MAX_SHORT_TEXT_CHARS));
            payload.put("androidSdk", Build.VERSION.SDK_INT);
            payload.put(
                    "appVersion",
                    truncate(
                            ServiceUtils.getAppVersionName(mContext),
                            AsgConstants.DIRECT_SERVER_MAX_GENERAL_TEXT_CHARS));
            return payload;
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build direct-server hello payload", e);
        }
    }

    /** Build a cached, nonblocking battery and Wi-Fi status payload. */
    public JSONObject buildStatusPayload() {
        boolean wifiConnected = safeIsWifiConnected();
        try {
            JSONObject payload = new JSONObject();
            payload.put("battery", clamp(safeBatteryLevel(), -1, 100));
            payload.put("charging", safeChargingState());
            payload.put("wifiConnected", wifiConnected);
            payload.put(
                    "wifiSsid",
                    wifiConnected
                            ? truncate(
                                    safeWifiSsid(),
                                    AsgConstants.DIRECT_SERVER_MAX_GENERAL_TEXT_CHARS)
                            : "");
            payload.put(
                    "wifiRssi",
                    wifiConnected
                            ? clamp(
                                    readWifiRssi(),
                                    AsgConstants.DIRECT_SERVER_UNKNOWN_WIFI_RSSI_DBM,
                                    0)
                            : AsgConstants.DIRECT_SERVER_UNKNOWN_WIFI_RSSI_DBM);
            payload.put(
                    "localIp",
                    wifiConnected
                            ? truncate(
                                    safeLocalIp(), AsgConstants.DIRECT_SERVER_MAX_SHORT_TEXT_CHARS)
                            : "");
            return payload;
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to build direct-server status payload", e);
        }
    }

    /** Return the stable product serial or generated install identity used by direct Cally. */
    public String getDeviceId() {
        return mDeviceId;
    }

    private int safeBatteryLevel() {
        try {
            return mStateManager == null ? -1 : mStateManager.getBatteryLevel();
        } catch (RuntimeException e) {
            Log.w("DirectDeviceStatus", "Unable to read cached battery level", e);
            return -1;
        }
    }

    private boolean safeChargingState() {
        try {
            return mStateManager != null && mStateManager.isCharging();
        } catch (RuntimeException e) {
            Log.w("DirectDeviceStatus", "Unable to read cached charging state", e);
            return false;
        }
    }

    private boolean safeIsWifiConnected() {
        try {
            return mNetworkManager != null && mNetworkManager.isConnectedToWifi();
        } catch (RuntimeException e) {
            Log.w("DirectDeviceStatus", "Unable to read Wi-Fi state", e);
            return false;
        }
    }

    private String safeWifiSsid() {
        try {
            String ssid = mNetworkManager == null ? "" : mNetworkManager.getCurrentWifiSsid();
            if (ssid == null || "<unknown ssid>".equalsIgnoreCase(ssid)) {
                return "";
            }
            return ssid;
        } catch (RuntimeException e) {
            Log.w("DirectDeviceStatus", "Unable to read Wi-Fi SSID", e);
            return "";
        }
    }

    private String safeLocalIp() {
        try {
            String localIp = mNetworkManager == null ? "" : mNetworkManager.getLocalIpAddress();
            return localIp == null ? "" : localIp;
        } catch (RuntimeException e) {
            Log.w("DirectDeviceStatus", "Unable to read local IP address", e);
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private int readWifiRssi() {
        try {
            WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return AsgConstants.DIRECT_SERVER_UNKNOWN_WIFI_RSSI_DBM;
            }
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            return wifiInfo == null
                    ? AsgConstants.DIRECT_SERVER_UNKNOWN_WIFI_RSSI_DBM
                    : wifiInfo.getRssi();
        } catch (RuntimeException e) {
            Log.w("DirectDeviceStatus", "Unable to read Wi-Fi RSSI", e);
            return AsgConstants.DIRECT_SERVER_UNKNOWN_WIFI_RSSI_DBM;
        }
    }

    private static String resolveDeviceId(Context context) {
        String productSerial = SysProp.getDeviceSerial(context);
        if (!productSerial.isEmpty()
                && productSerial.length() <= AsgConstants.DIRECT_SERVER_MAX_DEVICE_ID_CHARS) {
            return productSerial;
        }

        // AsgClientBootReceiver starts during LOCKED_BOOT_COMPLETED. Keep this POC-only identity in
        // device-protected storage so constructing the service graph never depends on user unlock.
        Context storageContext = context.createDeviceProtectedStorageContext();
        SharedPreferences preferences =
                storageContext.getSharedPreferences(
                        AsgConstants.DIRECT_SERVER_PREFERENCES, Context.MODE_PRIVATE);
        String existing = preferences.getString(AsgConstants.DIRECT_SERVER_INSTALL_ID_KEY, "");
        if (existing != null
                && !existing.trim().isEmpty()
                && existing.trim().length() <= AsgConstants.DIRECT_SERVER_MAX_DEVICE_ID_CHARS) {
            return existing.trim();
        }

        String generated = "install-" + UUID.randomUUID();
        preferences.edit().putString(AsgConstants.DIRECT_SERVER_INSTALL_ID_KEY, generated).apply();
        return generated;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String truncate(String value, int maximumChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximumChars ? value : value.substring(0, maximumChars);
    }
}
