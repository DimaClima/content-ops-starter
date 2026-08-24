package com.example.factradio.network;

import android.os.Handler;
import android.os.Looper;

import com.example.factradio.BuildConfig;
import com.example.factradio.model.DialogueLine;
import com.example.factradio.model.Episode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PodcastRenderClient {
    public interface Callback {
        void onSuccess(String audioUrl);
        void onError(String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public boolean isConfigured() {
        return BuildConfig.RADIO_API_BASE_URL != null
                && !BuildConfig.RADIO_API_BASE_URL.trim().isEmpty()
                && BuildConfig.RADIO_APP_TOKEN != null
                && !BuildConfig.RADIO_APP_TOKEN.trim().isEmpty();
    }

    public void render(Episode episode, String voicePreset, Callback callback) {
        if (!isConfigured()) {
            callback.onError("Сервер живого голоса не настроен");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String base = trimSlash(BuildConfig.RADIO_API_BASE_URL);
                connection = (HttpURLConnection) new URL(base + "/api/render").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(240_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("x-radio-token", BuildConfig.RADIO_APP_TOKEN);

                JSONArray dialogue = new JSONArray();
                for (DialogueLine line : episode.getDialogue()) {
                    dialogue.put(new JSONObject()
                            .put("speaker", line.getSpeaker())
                            .put("text", line.getText()));
                }
                JSONObject body = new JSONObject()
                        .put("episodeId", episode.getId())
                        .put("voicePreset", voicePreset)
                        .put("script", episode.getScript())
                        .put("dialogue", dialogue);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String payload = readAll(stream);
                if (status < 200 || status >= 300) {
                    JSONObject error = payload.isEmpty() ? null : new JSONObject(payload);
                    postError(callback, error == null
                            ? "Ошибка сервера голоса: " + status
                            : error.optString("error", "Ошибка сервера голоса"));
                    return;
                }
                String value = new JSONObject(payload).getString("audioUrl");
                String audioUrl = value.startsWith("/") ? base + value : value;
                main.post(() -> callback.onSuccess(audioUrl));
            } catch (Exception error) {
                postError(callback, error.getMessage() == null
                        ? "Не удалось создать живой голос" : error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void postError(Callback callback, String message) {
        main.post(() -> callback.onError(message));
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String trimSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }
}
