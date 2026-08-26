package com.example.factradio.playback;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

final class CloudAudioDownloader {
    private static final long MIN_WAV_BYTES = 44L;
    private static final long MAX_AUDIO_BYTES = 30L * 1024L * 1024L;

    private CloudAudioDownloader() {}

    static File download(String sourceUrl, File target) throws IOException {
        if (target.isFile() && target.length() > MIN_WAV_BYTES) return target;

        File partial = new File(target.getParentFile(), target.getName() + ".part");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Не удалось очистить временный аудиофайл");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(sourceUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(90_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "audio/wav,audio/*;q=0.9,*/*;q=0.1");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Сервер аудио вернул ошибку " + status);
            }
            // getContentLengthLong() appeared only in Android 7.0 (API 24).
            // The legacy method is enough here because our hard limit is 30 MB.
            long declaredLength = connection.getContentLength();
            if (declaredLength > MAX_AUDIO_BYTES) {
                throw new IOException("Аудиофайл слишком большой");
            }

            long total = 0L;
            byte[] buffer = new byte[32 * 1024];
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(partial)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_AUDIO_BYTES) throw new IOException("Аудиофайл слишком большой");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (total <= MIN_WAV_BYTES) throw new IOException("Получен пустой аудиофайл");

            if (target.exists() && !target.delete()) {
                throw new IOException("Не удалось заменить аудиофайл");
            }
            if (!partial.renameTo(target)) {
                throw new IOException("Не удалось сохранить аудиофайл");
            }
            return target;
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists() && !target.exists()) partial.delete();
        }
    }
}
