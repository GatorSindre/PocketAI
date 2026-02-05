package com.sindre.ankle_pocketai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SuppressLint("AccessibilityPolicy")
public class MyAccessibilityService extends AccessibilityService {

    private long timerVolumeUp = 0L;
    private long timerVolumeDown = 0L;
    private final int longPressDelay = 180;

    private final List<Boolean> morseBuffer = new ArrayList<>();
    private final List<Character> sentenceBuffer = new ArrayList<>();
    private String sentenceString = "";

    private final List<List<Boolean>> morseAlphabet = Arrays.asList(
            Arrays.asList(false, true),                 // A  .-
            Arrays.asList(true, false, false, false),   // B  -...
            Arrays.asList(true, false, true, false),    // C  -.-.
            Arrays.asList(true, false, false),          // D  -..
            Arrays.asList(false),                       // E  .
            Arrays.asList(false, false, true, false),   // F  ..-.
            Arrays.asList(true, true, false),           // G  --.
            Arrays.asList(false, false, false, false),  // H  ....
            Arrays.asList(false, false),                // I  ..
            Arrays.asList(false, true, true, true),     // J  .---
            Arrays.asList(true, false, true),           // K  -.-
            Arrays.asList(false, true, false, false),   // L  .-..
            Arrays.asList(true, true),                  // M  --
            Arrays.asList(true, false),                 // N  -.
            Arrays.asList(true, true, true),            // O  ---
            Arrays.asList(false, true, true, false),    // P  .--.
            Arrays.asList(true, true, false, true),     // Q  --.-
            Arrays.asList(false, true, false),          // R  .-.
            Arrays.asList(false, false, false),         // S  ...
            Arrays.asList(true),                        // T  -
            Arrays.asList(false, false, true),          // U  ..-
            Arrays.asList(false, false, false, true),   // V  ...-
            Arrays.asList(false, true, true),           // W  .--
            Arrays.asList(true, false, false, true),    // X  -..-
            Arrays.asList(true, false, true, true),     // Y  -.--
            Arrays.asList(true, true, false, false)     // Z  --..
    );

    // Listener interface for callback
    public interface Listener {
        void onResponse(String text); // Response as UTF-8 string
        void onError(Exception e);
    }

    // Single function to send bytes and get UTF-8 string response
    public static void sendData(
            String host,
            int port,
            byte[] data,
            Listener listener
    ) {
        new Thread(() -> {
            try (Socket socket = new Socket(host, port)) {
                socket.setSoTimeout(60000); // optional timeout in ms

                // SEND
                OutputStream out = socket.getOutputStream();
                out.write(data);
                out.flush();

                // RECEIVE
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[4096];

                int bytesRead = in.read(buffer); // blocks until data arrives
                if (bytesRead > 0) {
                    // Convert to UTF-8 string
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    listener.onResponse(response); // callback with string
                }

            } catch (Exception e) {
                listener.onError(e); // callback on error
            }
        }).start();
    }


    private Character morseToChar(List<Boolean> code) {
        int index = morseAlphabet.indexOf(code);
        return index != -1 ? (char) ('A' + index) : null;
    }

    private void sentenceBufferUpdate() {
        StringBuilder sb = new StringBuilder();
        for (char c : sentenceBuffer) {
            sb.append(c);
        }
        sentenceString = sb.toString();
        Log.d("PocketAI", "Buffer Updated: " + sentenceString);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onInterrupt() {
        // No-op
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.d("MS-PocketAI", "Key pressed down: " + event.getKeyCode());

            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                timerVolumeUp = SystemClock.elapsedRealtime();
                return true;
            }

            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                timerVolumeDown = SystemClock.elapsedRealtime();
                return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.d("MS-PocketAI", "Key pressed up: " + event.getKeyCode());

            // VOLUME UP
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                long timePressed = SystemClock.elapsedRealtime() - timerVolumeUp;

                if (timePressed >= longPressDelay) {
                    // Long press
                    if (morseBuffer.isEmpty() && !sentenceBuffer.isEmpty()) {
                        sentenceBuffer.remove(sentenceBuffer.size() - 1);
                    } else if (morseBuffer.equals(Arrays.asList(false))) {
                        sentenceBuffer.clear();
                        morseBuffer.clear();
                    } else if (morseBuffer.equals(Arrays.asList(false, false))) {
                        sentenceBufferUpdate();
                        Log.d("PocketAI", "Sent '" + sentenceString + "' to ChatGPT");

                        byte[] messageBytes = sentenceString.getBytes(StandardCharsets.UTF_8);
                        sentenceBuffer.clear();

                        sendData("10.0.0.6", 8081, messageBytes, new Listener() {
                            @Override
                            public void onResponse(String text) {
                                Log.d("PocketAI", "Server responded: " + text);
                                // You could also trigger a notification, Toast, or other action here
                            }

                            @Override
                            public void onError(Exception e) {
                                Log.e("PocketAI", "Error sending data", e);
                            }
                        });

                    }
                } else {
                    // Short press
                    Character c = morseToChar(new ArrayList<>(morseBuffer));
                    sentenceBuffer.add(c != null ? c : ' ');
                }

                morseBuffer.clear();
                sentenceBufferUpdate();
                return true;
            }


            // VOLUME DOWN
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                long timePressed = SystemClock.elapsedRealtime() - timerVolumeDown;

                if (timePressed >= longPressDelay) {
                    morseBuffer.add(true);   // dash
                } else {
                    morseBuffer.add(false);  // dot
                }
                return true;
            }
        }

        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op
    }
}
