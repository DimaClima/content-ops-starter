package com.example.factradio.music;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.example.factradio.data.PreferenceStore;

public final class MusicRatingReceiver extends BroadcastReceiver {
    public static final String ACTION_RATE_MUSIC = "com.example.factradio.RATE_MUSIC";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_RATING = "rating";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RATE_MUSIC.equals(intent.getAction())) return;
        String title = intent.getStringExtra(EXTRA_TITLE);
        String artist = intent.getStringExtra(EXTRA_ARTIST);
        int rating = intent.getIntExtra(EXTRA_RATING, 0);
        if (title == null || title.trim().isEmpty() || rating == 0) return;

        int saved = new PreferenceStore(context).rateMusic(
                title,
                artist == null ? "" : artist,
                rating
        );
        Toast.makeText(
                context,
                saved > 0
                        ? "Трек отмечен: нравится"
                        : saved < 0 ? "Трек отмечен: не моё" : "Оценка трека стала нейтральной",
                Toast.LENGTH_SHORT
        ).show();
    }
}
