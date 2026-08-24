package com.example.factradio.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.example.factradio.MainActivity;

import java.util.Locale;

public final class YandexNotificationListener extends NotificationListenerService {
    public static final String MUSIC_PREFS = "yandex_music_state";
    public static final String KEY_TITLE = "current_title";
    public static final String KEY_ARTIST = "current_artist";
    public static final String ACTION_TRACK_CHANGED = "com.example.factradio.YANDEX_TRACK_CHANGED";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";

    private static final String CHANNEL_ID = "fact_radio_music_rating";
    private static final int NOTIFICATION_ID = 202;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Оценка треков",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Кнопки нравится и не нравится для текущего трека Яндекс Музыки");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isYandexMusicPackage(sbn.getPackageName())) return;
        Bundle extras = sbn.getNotification().extras;
        String title = asString(extras.getCharSequence(Notification.EXTRA_TITLE));
        String artist = asString(extras.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty()) return;

        String previousTitle = getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE)
                .getString(KEY_TITLE, "");
        String previousArtist = getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE)
                .getString(KEY_ARTIST, "");

        getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_TITLE, title)
                .putString(KEY_ARTIST, artist)
                .apply();
        if (!title.equals(previousTitle) || !artist.equals(previousArtist)) {
            Intent changed = new Intent(ACTION_TRACK_CHANGED)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_ARTIST, artist);
            sendBroadcast(changed);
        }
        showRatingNotification(title, artist);
    }

    private boolean isYandexMusicPackage(String packageName) {
        if (packageName == null) return false;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        return normalized.equals("ru.yandex.music")
                || (normalized.contains("yandex") && normalized.contains("music"));
    }

    private void showRatingNotification(String title, String artist) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(
                this, 20, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action like = ratingAction("👍 Нравится", title, artist, 1, 21);
        Notification.Action dislike = ratingAction("👎 Не моё", title, artist, -1, 22);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.btn_star_big_on)
                .setContentTitle(title)
                .setContentText(artist.isEmpty() ? "Яндекс Музыка" : artist)
                .setSubText("Оценка для рекомендаций ФактРадио")
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .addAction(like)
                .addAction(dislike)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private Notification.Action ratingAction(
            String label,
            String title,
            String artist,
            int rating,
            int requestCode
    ) {
        Intent intent = new Intent(this, MusicRatingReceiver.class)
                .setAction(MusicRatingReceiver.ACTION_RATE_MUSIC)
                .putExtra(MusicRatingReceiver.EXTRA_TITLE, title)
                .putExtra(MusicRatingReceiver.EXTRA_ARTIST, artist)
                .putExtra(MusicRatingReceiver.EXTRA_RATING, rating);
        PendingIntent pending = PendingIntent.getBroadcast(
                this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = rating > 0 ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float;
        return new Notification.Action.Builder(icon, label, pending).build();
    }

    private static String asString(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
