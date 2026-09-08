package com.mentra.asg_client.io.direct;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Base64;
import com.mentra.asg_client.AsgConstants;
import com.mentra.asg_client.camera.CameraNeoService;
import com.mentra.asg_client.io.hardware.interfaces.IHardwareManager;
import com.mentra.asg_client.io.streaming.services.RtmpStreamingService;
import com.mentra.asg_client.io.streaming.services.SrtStreamingService;
import com.mentra.asg_client.io.streaming.services.WhipStreamingService;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Bounded Mentra hardware adapter for direct peripheral diagnostics. */
public final class DirectPeripheralTester {
    private final Context mContext;
    private final IHardwareManager mHardware;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mBusy = new AtomicBoolean();
    private volatile boolean mClosed;
    private long mButtonCount;
    private long mButtonTimestamp;
    private String mButtonType = "none";

    /** Reuse the service's hardware ownership instead of creating another MCU connection. */
    public DirectPeripheralTester(Context context, IHardwareManager hardware) {
        mContext = context;
        mHardware = hardware;
    }

    /** Observe input without consuming it or changing existing phone/gallery behavior. */
    public synchronized void onButton(boolean longPress) {
        mButtonCount++;
        mButtonTimestamp = System.currentTimeMillis();
        mButtonType = longPress ? "CAMERA_LONG_PRESS" : "CAMERA_SHORT_PRESS";
    }

    /** Run one fixed test; overlapping hardware requests are rejected rather than queued. */
    public void execute(String kind, Consumer<JSONObject> reply) {
        if (mClosed || !mBusy.compareAndSet(false, true)) {
            reply.accept(result(kind, false, "busy"));
            return;
        }
        mWorker.execute(() -> {
            try {
                if ("buttons".equals(kind)) {
                    JSONObject response = result(kind, true, null);
                    synchronized (this) {
                        response.put("count", mButtonCount).put("event", mButtonType)
                                .put("eventTimestamp", mButtonTimestamp);
                    }
                    finish(reply, response);
                } else if ("led".equals(kind)) {
                    if (!mHardware.supportsRgbLed()) throw new IllegalStateException("unsupported");
                    mHardware.setRgbLedOn(1, AsgConstants.DIRECT_TEST_LED_DURATION_MS,
                            AsgConstants.DIRECT_TEST_LED_DURATION_MS, 2,
                            AsgConstants.DIRECT_TEST_LED_BRIGHTNESS);
                    finish(reply, result(kind, true, null));
                } else if (RtmpStreamingService.isStreaming() || SrtStreamingService.isStreaming()
                        || WhipStreamingService.isStreaming()) {
                    finish(reply, result(kind, false, "streaming_active"));
                } else if ("photo".equals(kind)) {
                    capturePhoto(reply);
                } else if ("voice".equals(kind)) {
                    recordVoice(reply);
                } else if ("mic".equals(kind)) {
                    recordMicrophone(reply);
                } else {
                    finish(reply, result(kind, false, "unsupported"));
                }
            } catch (Exception error) {
                finish(reply, result(kind, false, error.getClass().getSimpleName()));
            }
        });
    }

    private void capturePhoto(Consumer<JSONObject> reply) throws Exception {
        File directory = new File(mContext.getCacheDir(), "cally-peripheral-tests");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("storage");
        File target = File.createTempFile("capture-", ".jpg", directory);
        CameraNeoService.enqueuePhotoRequest(mContext, target.getAbsolutePath(),
                AsgConstants.DIRECT_AGENT_PHOTO_SIZE, true,
                true, null, new CameraNeoService.PhotoCaptureCallback() {
            @Override public void onPhotoCaptured(String path) {
                if (mClosed) { target.delete(); mBusy.set(false); return; }
                mWorker.execute(() -> {
                    try {
                        byte[] bytes = Files.readAllBytes(new File(path).toPath());
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(path, bounds);
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            throw new IllegalStateException("decode_failed");
                        }
                        JSONObject response = mediaResult("photo", "image/jpeg", bytes);
                        response.put("width", bounds.outWidth).put("height", bounds.outHeight);
                        finish(reply, response);
                    } catch (Exception error) {
                        finish(reply, result("photo", false, error.getClass().getSimpleName()));
                    } finally {
                        target.delete();
                    }
                });
            }
            @Override public void onPhotoError(String error) {
                target.delete();
                finish(reply, result("photo", false, "camera_error"));
            }
        });
    }

    private void recordMicrophone(Consumer<JSONObject> reply) throws Exception {
        int rate = AsgConstants.DIRECT_TEST_AUDIO_RATE;
        int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("unsupported_audio_format");
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum, rate));
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("microphone_unavailable");
            byte[] data = new byte[rate * 2 * AsgConstants.DIRECT_TEST_AUDIO_SECONDS];
            recorder.startRecording();
            int total = 0;
            long deadline = SystemClock.elapsedRealtime()
                    + (AsgConstants.DIRECT_TEST_AUDIO_SECONDS + 2L) * 1000L;
            while (!mClosed && total < data.length && SystemClock.elapsedRealtime() < deadline) {
                int read = recorder.read(data, total, data.length - total, AudioRecord.READ_NON_BLOCKING);
                if (read < 0) throw new IllegalStateException("microphone_read_failed");
                total += read;
                if (read == 0) Thread.sleep(10);
            }
            if (total != data.length) throw new IllegalStateException("microphone_incomplete");
            JSONObject response = mediaResult("mic", "audio/pcm", data);
            response.put("sampleRate", rate).put("channels", 1).put("bitsPerSample", 16);
            finish(reply, response);
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
            recorder.release();
        }
    }

    private void recordVoice(Consumer<JSONObject> reply) throws Exception {
        int rate = AsgConstants.DIRECT_TEST_AUDIO_RATE;
        int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("unsupported_audio_format");
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, rate));
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("microphone_unavailable");
            byte[] data = new byte[rate * 2 * AsgConstants.DIRECT_VOICE_MAX_SECONDS];
            VoiceEndpoint endpoint = new VoiceEndpoint(rate);
            recorder.startRecording();
            if (mHardware.supportsRgbLed()) mHardware.setRgbLedOn(2,
                    AsgConstants.DIRECT_VOICE_MAX_SECONDS * 1000, 0, 1,
                    AsgConstants.DIRECT_TEST_LED_BRIGHTNESS);
            int total = 0;
            long deadline = SystemClock.elapsedRealtime() + AsgConstants.DIRECT_VOICE_MAX_SECONDS * 1000L;
            while (!mClosed && total < data.length && SystemClock.elapsedRealtime() < deadline) {
                int read = recorder.read(data, total, Math.min(640, data.length - total), AudioRecord.READ_NON_BLOCKING);
                if (read < 0) throw new IllegalStateException("microphone_read_failed");
                if (read == 0) { Thread.sleep(10); continue; }
                boolean stop = endpoint.accept(data, total, read);
                total += read;
                if (stop) break;
            }
            if (mClosed) throw new IllegalStateException("recording_cancelled");
            if (!endpoint.hasSpeech()) throw new IllegalStateException("no_speech_detected");
            JSONObject response = mediaResult("voice", "audio/pcm", Arrays.copyOf(data, total));
            response.put("sampleRate", rate).put("channels", 1).put("bitsPerSample", 16);
            finish(reply, response);
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
            recorder.release();
            if (mHardware.supportsRgbLed()) mHardware.setRgbLedOff();
        }
    }

    private JSONObject mediaResult(String kind, String mime, byte[] bytes) throws Exception {
        if (bytes.length > ("voice".equals(kind) ? AsgConstants.DIRECT_VOICE_MEDIA_MAX_BYTES : AsgConstants.DIRECT_TEST_MEDIA_MAX_BYTES))
            throw new IllegalStateException("media_too_large");
        return result(kind, true, null).put("mimeType", mime).put("bytes", bytes.length)
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
    }

    private JSONObject result(String kind, boolean success, String error) {
        try {
            JSONObject response = new JSONObject().put("kind", kind).put("success", success);
            if (error != null) response.put("error", error);
            return response;
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private void finish(Consumer<JSONObject> reply, JSONObject response) {
        mBusy.set(false);
        if (!mClosed) reply.accept(response);
    }

    /** Stop bounded capture work when the owning service shuts down. */
    public void close() {
        mClosed = true;
        mWorker.shutdownNow();
    }
}
