package com.mentra.asg_client.io.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.io.network.interfaces.INetworkManager;
import com.mentra.asg_client.service.system.interfaces.IStateManager;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Robolectric coverage for nonblocking direct-device status snapshots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DirectDeviceStatusProviderTest {

    /** Cached battery and network-manager values are serialized without a hardware query. */
    @Test
    public void statusUsesCachedStateAndNetworkFacts() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        IStateManager stateManager = Mockito.mock(IStateManager.class);
        INetworkManager networkManager = Mockito.mock(INetworkManager.class);
        Mockito.when(stateManager.getBatteryLevel()).thenReturn(82);
        Mockito.when(stateManager.isCharging()).thenReturn(false);
        Mockito.when(networkManager.isConnectedToWifi()).thenReturn(true);
        Mockito.when(networkManager.getCurrentWifiSsid()).thenReturn("home-wifi");
        Mockito.when(networkManager.getLocalIpAddress()).thenReturn("192.168.1.42");

        DirectDeviceStatusProvider provider =
                new DirectDeviceStatusProvider(context, stateManager, networkManager);
        JSONObject status = provider.buildStatusPayload();

        assertEquals(82, status.getInt("battery"));
        assertFalse(status.getBoolean("charging"));
        assertEquals("home-wifi", status.getString("wifiSsid"));
        assertEquals("192.168.1.42", status.getString("localIp"));
        int rssi = status.getInt("wifiRssi");
        assertTrue(rssi >= AsgConstants.DIRECT_SERVER_UNKNOWN_WIFI_RSSI_DBM);
        assertTrue(rssi <= 0);
    }

    /** Generated fallback identity is nonempty and stable across provider instances. */
    @Test
    public void fallbackInstallIdentityIsStable() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AsgConstants.DIRECT_SERVER_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();

        DirectDeviceStatusProvider first = new DirectDeviceStatusProvider(context, null, null);
        DirectDeviceStatusProvider second = new DirectDeviceStatusProvider(context, null, null);

        String firstId = first.buildHelloPayload().getString("deviceId");
        String secondId = second.buildHelloPayload().getString("deviceId");
        assertFalse(firstId.isEmpty());
        assertEquals(firstId, secondId);
    }
}
