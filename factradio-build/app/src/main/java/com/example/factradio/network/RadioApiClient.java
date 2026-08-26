package com.example.factradio.network;

import android.os.Handler;
import android.os.Looper;

import com.example.factradio.BuildConfig;
import com.example.factradio.model.Episode;
import com.example.factradio.model.DialogueLine;
import com.example.factradio.model.Source;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RadioApiClient {
    public interface Callback {
        void onSuccess(Episode episode);
        void onError(String message);
    }

    public interface FeedCallback {
        void onSuccess(ArrayList<Episode> episodes);
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

    public void requestStory(String spokenQuery, String profile, String voicePreset, Callback callback) {
        requestStory(spokenQuery, profile, voicePreset, UUID.randomUUID().toString(),
                new ArrayList<>(), callback);
    }

    public void requestStory(String spokenQuery, String profile, String voicePreset,
                             String requestNonce, List<String> excludedTitles,
                             Callback callback) {
        if (!isConfigured()) {
            callback.onError("Сервер генерации пока не настроен");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(trimSlash(BuildConfig.RADIO_API_BASE_URL) + "/api/story");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(240_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("x-radio-token", BuildConfig.RADIO_APP_TOKEN);

                JSONObject body = new JSONObject()
                        .put("query", spokenQuery)
                        .put("profile", profile == null ? "" : profile)
                        .put("voicePreset", voicePreset == null ? "duo_best" : voicePreset)
                        .put("requestNonce", requestNonce == null || requestNonce.trim().isEmpty()
                                ? UUID.randomUUID().toString() : requestNonce)
                        .put("excludedTitles", new JSONArray(
                                excludedTitles == null ? new ArrayList<>() : excludedTitles))
                        .put("language", "ru")
                        .put("targetMinutes", 3);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String payload = readAll(stream);
                if (status < 200 || status >= 300) {
                    JSONObject error = payload.isEmpty() ? null : new JSONObject(payload);
                    String message = error == null
                            ? "Ошибка сервера: " + status
                            : error.optString("error", "Ошибка сервера: " + status);
                    postError(callback, message);
                    return;
                }
                Episode episode = parseEpisode(
                        new JSONObject(payload).getJSONObject("episode"),
                        trimSlash(BuildConfig.RADIO_API_BASE_URL)
                );
                main.post(() -> callback.onSuccess(episode));
            } catch (Exception error) {
                postError(callback, error.getMessage() == null ? "Не удалось получить рассказ" : error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void requestFeed(int requestedLimit, FeedCallback callback) {
        if (!isConfigured()) {
            callback.onError("Сервер подборки пока не настроен");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String base = trimSlash(BuildConfig.RADIO_API_BASE_URL);
                connection = (HttpURLConnection) new URL(base + "/api/feed").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(60_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("x-radio-token", BuildConfig.RADIO_APP_TOKEN);
                JSONObject body = new JSONObject().put("limit", Math.max(1, Math.min(60, requestedLimit)));
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String payload = readAll(stream);
                if (status < 200 || status >= 300) {
                    JSONObject error = payload.isEmpty() ? null : new JSONObject(payload);
                    String message = error == null
                            ? "Ошибка базы рассказов: " + status
                            : error.optString("error", "Ошибка базы рассказов: " + status);
                    main.post(() -> callback.onError(message));
                    return;
                }

                JSONArray rawEpisodes = new JSONObject(payload).optJSONArray("episodes");
                ArrayList<Episode> episodes = new ArrayList<>();
                if (rawEpisodes != null) {
                    for (int i = 0; i < rawEpisodes.length(); i++) {
                        episodes.add(parseEpisode(rawEpisodes.getJSONObject(i), base));
                    }
                }
                main.post(() -> callback.onSuccess(episodes));
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "Не удалось загрузить базу рассказов" : error.getMessage();
                main.post(() -> callback.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private Episode parseEpisode(JSONObject json, String baseUrl) throws Exception {
        ArrayList<String> tags = new ArrayList<>();
        JSONArray rawTags = json.optJSONArray("tags");
        if (rawTags != null) {
            for (int i = 0; i < rawTags.length(); i++) tags.add(rawTags.getString(i));
        }

        ArrayList<Source> sources = new ArrayList<>();
        JSONArray rawSources = json.optJSONArray("sources");
        if (rawSources != null) {
            for (int i = 0; i < rawSources.length(); i++) {
                JSONObject source = rawSources.getJSONObject(i);
                sources.add(new Source(source.optString("title", "Источник"), source.getString("url")));
            }
        }

        ArrayList<DialogueLine> dialogue = new ArrayList<>();
        JSONArray rawDialogue = json.optJSONArray("dialogue");
        if (rawDialogue != null) {
            for (int i = 0; i < rawDialogue.length(); i++) {
                JSONObject line = rawDialogue.getJSONObject(i);
                dialogue.add(new DialogueLine(line.optString("speaker", DialogueLine.MALE), line.getString("text")));
            }
        }

        String audioUrl = json.optString("audioUrl", "");
        if (audioUrl.startsWith("/")) audioUrl = baseUrl + audioUrl;

        return new Episode(
                json.optString("id", "generated-" + System.currentTimeMillis()),
                json.optString("category", "По вашему запросу"),
                json.getString("title"),
                json.optString("summary", "Документальный рассказ по вашему запросу"),
                json.getString("script"),
                audioUrl,
                dialogue,
                tags,
                sources
        );
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
