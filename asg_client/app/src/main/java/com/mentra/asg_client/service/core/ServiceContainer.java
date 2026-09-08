package com.mentra.asg_client.service.core;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.mentra.asg_client.io.file.core.FileManager;
import com.mentra.asg_client.io.file.core.FileManagerFactory;
import com.mentra.asg_client.audio.AudioAssets;
import com.mentra.asg_client.io.direct.DirectDeviceStatusProvider;
import com.mentra.asg_client.io.direct.DirectPcmPlayer;
import com.mentra.asg_client.io.direct.DirectPeripheralTester;
import com.mentra.asg_client.io.direct.DirectServerConfig;
import com.mentra.asg_client.io.direct.DirectWebSocketTransport;
import com.mentra.asg_client.io.hardware.core.HardwareManagerFactory;
import com.mentra.asg_client.io.hardware.interfaces.IHardwareManager;
import com.mentra.asg_client.io.ota.helpers.OtaHelper;
import com.mentra.asg_client.service.communication.interfaces.ICommunicationManager;
import com.mentra.asg_client.service.core.handlers.OtaCommandHandler;
import com.mentra.asg_client.service.core.handlers.K900CommandHandler;
import com.mentra.asg_client.service.communication.interfaces.IResponseBuilder;
import com.mentra.asg_client.service.communication.managers.CommunicationManager;
import com.mentra.asg_client.service.communication.managers.ResponseBuilder;
import com.mentra.asg_client.service.core.processors.CommandProcessor;
import com.mentra.asg_client.service.core.handlers.RgbLedCommandHandler;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.service.media.interfaces.IMediaManager;
import com.mentra.asg_client.service.media.managers.MediaManager;
import com.mentra.asg_client.service.system.interfaces.IConfigurationManager;
import com.mentra.asg_client.service.system.interfaces.IServiceLifecycle;
import com.mentra.asg_client.service.system.interfaces.IStateManager;
import com.mentra.asg_client.service.system.managers.AsgNotificationManager;
import com.mentra.asg_client.service.system.managers.ConfigurationManager;
import com.mentra.asg_client.service.system.managers.ServiceLifecycleManager;
import com.mentra.asg_client.service.system.managers.StateManager;


/**
 * Dependency injection container for service components.
 * Follows Dependency Inversion Principle by managing dependencies through interfaces.
 */
public class ServiceContainer {

    private final Context context;
    private final AsgClientServiceManager serviceManager;
    private final CommandProcessor commandProcessor;
    private final IResponseBuilder responseBuilder;
    private final AsgNotificationManager notificationManager;

    // Interface implementations
    private final IServiceLifecycle lifecycleManager;
    private final ICommunicationManager communicationManager;
    private final IConfigurationManager configurationManager;
    private final IStateManager stateManager;
    private final IMediaManager streamingManager;

    private final FileManager fileManager;
    private final DirectWebSocketTransport directWebSocketTransport;
    private final DirectPeripheralTester directPeripheralTester;
    private final DirectPcmPlayer directPcmPlayer;

    public ServiceContainer(Context context, @NonNull AsgClientService service) {
        this.context = context;

        this.fileManager = FileManagerFactory.getInstance();

        // Initialize interface implementations first
        this.communicationManager = new CommunicationManager(null); // Will be updated after serviceManager creation

        // Initialize core components with service reference
        this.serviceManager = new AsgClientServiceManager(context, service, communicationManager, fileManager);
        this.notificationManager = new AsgNotificationManager(context);

        // Update communication manager with service manager reference
        ((CommunicationManager) this.communicationManager).setServiceManager(serviceManager);
        this.configurationManager = new ConfigurationManager(context);
        this.stateManager = new StateManager(serviceManager);

        // Set StateManager in service manager for battery monitoring
        serviceManager.setStateManager(this.stateManager);

        IHardwareManager hardwareManager = HardwareManagerFactory.getInstance(context);
        this.directWebSocketTransport = new DirectWebSocketTransport(
                context,
                DirectServerConfig.fromBuildConfig(),
                new DirectDeviceStatusProvider(context, stateManager, serviceManager.getNetworkManager()));
        this.directPeripheralTester = new DirectPeripheralTester(context, hardwareManager);
        this.directPcmPlayer = new DirectPcmPlayer();
        this.directWebSocketTransport.setPeripheralTester(directPeripheralTester);
        this.directWebSocketTransport.setPcmPlayer(directPcmPlayer);
        this.directWebSocketTransport.setTestSoundPlayer(() -> {
            if (!hardwareManager.supportsAudioPlayback()) return false;
            hardwareManager.playAudioAsset(AudioAssets.CLICK_SOUND);
            return true;
        });
        K900CommandHandler.setDirectPeripheralTester(directPeripheralTester);

        this.streamingManager = new MediaManager(context, serviceManager);


        // Create RGB LED command handler and set reference in service manager
        RgbLedCommandHandler rgbLedHandler = new RgbLedCommandHandler(serviceManager);
        Log.i("ServiceContainer", "🚨 Created RGB LED command handler: " + (rgbLedHandler != null ? "✅ SUCCESS" : "❌ FAILED"));
        serviceManager.setRgbLedCommandHandler(rgbLedHandler);
        Log.i("ServiceContainer", "🚨 Set RGB LED handler in service manager: " + (serviceManager.getRgbLedCommandHandler() != null ? "✅ SUCCESS" : "❌ FAILED"));

        // Initialize CommandProcessor with interface-based managers
        this.responseBuilder = new ResponseBuilder();
        this.commandProcessor = new CommandProcessor(context,
                communicationManager,
                stateManager,
                streamingManager,
                responseBuilder,
                configurationManager,
                serviceManager,
                fileManager,
                rgbLedHandler);

        // Initialize lifecycle manager with all components
        this.lifecycleManager = new ServiceLifecycleManager(context, serviceManager, commandProcessor, notificationManager);
    }

    /**
     * Get service lifecycle manager
     */
    public IServiceLifecycle getLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * Get communication manager
     */
    public ICommunicationManager getCommunicationManager() {
        return communicationManager;
    }

    /**
     * Get configuration manager
     */
    public IConfigurationManager getConfigurationManager() {
        return configurationManager;
    }

    public IResponseBuilder getResponseBuilder() {
        return responseBuilder;
    }

    /**
     * Get state manager
     */
    public IStateManager getStateManager() {
        return stateManager;
    }

    /**
     * Get streaming manager
     */
    public IMediaManager getStreamingManager() {
        return streamingManager;
    }

    /**
     * Get service manager
     */
    public AsgClientServiceManager getServiceManager() {
        return serviceManager;
    }

    /**
     * Get command processor
     */
    public CommandProcessor getCommandProcessor() {
        return commandProcessor;
    }

    /**
     * Get notification manager
     */
    public AsgNotificationManager getNotificationManager() {
        return notificationManager;
    }

    /**
     * Initialize all components
     */
    public void initialize() {
        Log.d("ServiceContainer", "Initializing service container");

        // Initialize lifecycle manager first
        lifecycleManager.initialize();
        directWebSocketTransport.start();

        // Wire up phone-controlled OTA after OtaService has started (delayed)
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            wireUpPhoneControlledOta();
        }, 6000); // After OtaService starts (5s delay + 1s buffer)

        Log.d("ServiceContainer", "Service container initialized successfully");
    }

    /**
     * Wire up phone-controlled OTA connections.
     * Called after OtaService has started and OtaHelper singleton is available.
     */
    private void wireUpPhoneControlledOta() {
        Log.d("ServiceContainer", "Wiring up phone-controlled OTA...");

        // Always set CommunicationManager on OtaCommandHandler for error reporting
        // This is needed even if OtaHelper isn't ready yet
        OtaCommandHandler.setCommunicationManager(communicationManager);
        Log.i("ServiceContainer", "✅ CommunicationManager set on OtaCommandHandler");

        OtaHelper otaHelper = OtaHelper.getInstance();
        if (otaHelper != null) {
            // Set CommunicationManager as the PhoneConnectionProvider
            otaHelper.setPhoneConnectionProvider((CommunicationManager) communicationManager);
            Log.i("ServiceContainer", "✅ PhoneConnectionProvider set on OtaHelper");

            // Set OtaHelper on OtaCommandHandler for handling ota_start commands
            OtaCommandHandler.setOtaHelper(otaHelper);
            Log.i("ServiceContainer", "✅ OtaHelper set on OtaCommandHandler");
        } else {
            Log.w("ServiceContainer", "⚠️ OtaHelper not yet initialized - phone-controlled OTA not available");
        }
    }

    /**
     * Clean up all components
     */
    public void cleanup() {
        Log.d("ServiceContainer", "Cleaning up service container");

        // Clean up streaming manager first (unregisters callbacks)
        streamingManager.cleanup();
        directWebSocketTransport.stop();
        directPcmPlayer.close();
        directPeripheralTester.close();
        K900CommandHandler.setDirectPeripheralTester(null);

        // Clean up lifecycle manager
        lifecycleManager.cleanup();

        Log.d("ServiceContainer", "Service container cleanup completed");
    }
}
