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
import android.speech.tts.TextToSpeech;
import java.util.Locale;

@SuppressLint("AccessibilityPolicy")
public class MyAccessibilityService extends AccessibilityService {

    private TextToSpeech tts;
    private boolean ttsReady = false;
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

    public static void sendData(String host, int port, byte[] data, Listener listener) {
        new Thread(() -> {
            Socket socket = null;
            try {
                socket = new Socket(host, port);
                socket.setSoTimeout(120000); // optional read timeout

                // SEND
                OutputStream out = socket.getOutputStream();
                out.write(data);
                out.flush();

                // RECEIVE
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[4096];

                int bytesRead = in.read(buffer); // will block until server responds or timeout
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    listener.onResponse(response);
                }

            } catch (Exception e) {
                listener.onError(e);
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

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("PocketAI", "TTS language not supported");
                } else {
                    ttsReady = true;
                }
            } else {
                Log.e("PocketAI", "TTS initialization failed");
            }
        });
    }

    private void speak(String text) {
        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1");
        } else {
            Log.d("PocketAI", "TTS not ready yet");
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
                                // text is the response from chatgpt
                                Log.d("PocketAI", "Server responded: " + text);
                                speak(text);
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

    @Override
    public void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
