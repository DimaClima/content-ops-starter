package com.example.factradio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;

import com.example.factradio.data.DemoEpisodes;
import com.example.factradio.data.PreferenceStore;
import com.example.factradio.model.Episode;
import com.example.factradio.model.Source;
import com.example.factradio.music.YandexNotificationListener;
import com.example.factradio.network.RadioApiClient;
import com.example.factradio.playback.RadioPlaybackService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_SPEECH = 501;
    private static final int REQUEST_AUDIO_PERMISSION = 502;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 503;

    private TextView categoryText;
    private TextView titleText;
    private TextView descriptionText;
    private TextView positionText;
    private TextView durationText;
    private TextView statusText;
    private TextView musicTrackText;
    private TextView musicArtistText;
    private SeekBar progressSeek;
    private Button playButton;
    private Button likeButton;
    private Button dislikeButton;
    private Button voicePresetButton;
    private Switch musicMixSwitch;

    private Episode currentEpisode;
    private boolean playing;
    private boolean musicBreak;
    private boolean generationWait;
    private int musicTarget;
    private int musicCompleted;
    private String currentMusicTitle = "";
    private String currentMusicArtist = "";
    private boolean draggingSeek;
    private boolean receiverRegistered;
    private boolean recommendationPending;
    private OrientationEventListener orientationListener;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private int orientationCandidate = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private long orientationCandidateSince;
    private boolean hasEnteredForeground;
    private long stoppedAt;
    private PreferenceStore preferenceStore;
    private RadioApiClient apiClient;

    private final SensorEventListener accelerometerListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.values.length < 2) return;
            int target = requestedOrientationForGravity(event.values[0], event.values[1]);
            if (target == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return;
            applyStableOrientation(target);
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final BroadcastReceiver playbackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Episode episode = (Episode) intent.getSerializableExtra(RadioPlaybackService.EXTRA_EPISODE);
            if (episode != null) {
                currentEpisode = episode;
                renderEpisode();
            }
            playing = intent.getBooleanExtra(RadioPlaybackService.EXTRA_PLAYING, false);
            musicBreak = intent.getBooleanExtra(RadioPlaybackService.EXTRA_MUSIC_BREAK, false);
            generationWait = intent.getBooleanExtra(RadioPlaybackService.EXTRA_GENERATION_WAIT, false);
            recommendationPending = intent.getBooleanExtra(
                    RadioPlaybackService.EXTRA_RECOMMENDATION_PENDING, false);
            musicTarget = intent.getIntExtra(RadioPlaybackService.EXTRA_MUSIC_TARGET, 0);
            musicCompleted = intent.getIntExtra(RadioPlaybackService.EXTRA_MUSIC_COMPLETED, 0);
            if (musicBreak) readMusicTrack();
            int position = intent.getIntExtra(RadioPlaybackService.EXTRA_POSITION, 0);
            int duration = intent.getIntExtra(RadioPlaybackService.EXTRA_DURATION, 0);
            renderPlayback(position, duration);
            renderRating();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferenceStore = new PreferenceStore(this);
        apiClient = new RadioApiClient();
        currentEpisode = null;

        bindViews();
        bindActions();
        renderEpisode();
        configurePhysicalOrientation();
        requestNotificationPermissionIfNeeded();
        if (savedInstanceState == null) {
            sendPlaybackAction(RadioPlaybackService.ACTION_START_NEW_SESSION);
        }
    }

    private void bindViews() {
        categoryText = findViewById(R.id.categoryText);
        titleText = findViewById(R.id.titleText);
        descriptionText = findViewById(R.id.descriptionText);
        positionText = findViewById(R.id.positionText);
        durationText = findViewById(R.id.durationText);
        statusText = findViewById(R.id.statusText);
        musicTrackText = findViewById(R.id.musicTrackText);
        musicArtistText = findViewById(R.id.musicArtistText);
        progressSeek = findViewById(R.id.progressSeek);
        playButton = findViewById(R.id.playButton);
        likeButton = findViewById(R.id.likeButton);
        dislikeButton = findViewById(R.id.dislikeButton);
        voicePresetButton = findViewById(R.id.voicePresetButton);
        musicMixSwitch = findViewById(R.id.musicMixSwitch);
        musicMixSwitch.setChecked(preferenceStore.isMusicMixEnabled());
        TextView versionText = findViewById(R.id.versionText);
        versionText.setText("Версия " + BuildConfig.VERSION_NAME + " · Android/Android Auto");
        renderVoicePreset();
    }

    private void bindActions() {
        playButton.setOnClickListener(view -> sendPlaybackAction(
                playing ? RadioPlaybackService.ACTION_PAUSE : RadioPlaybackService.ACTION_PLAY));
        findViewById(R.id.nextButton).setOnClickListener(
                view -> sendPlaybackAction(RadioPlaybackService.ACTION_NEXT));
        findViewById(R.id.replayButton).setOnClickListener(
                view -> sendPlaybackAction(RadioPlaybackService.ACTION_REPLAY));
        likeButton.setOnClickListener(view -> rateCurrentMaterial(1));
        dislikeButton.setOnClickListener(view -> rateCurrentMaterial(-1));
        findViewById(R.id.sourcesButton).setOnClickListener(view -> showSources());
        findViewById(R.id.voiceButton).setOnClickListener(view -> startVoiceRequest());
        voicePresetButton.setOnClickListener(view -> showVoicePresetDialog());
        musicMixSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferenceStore.setMusicMixEnabled(checked);
            Toast.makeText(this,
                    checked ? "После истории прозвучат 1–3 песни" : "Музыкальные паузы выключены",
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.openYandexButton).setOnClickListener(view -> openYandexMusic());
        findViewById(R.id.musicAccessButton).setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        progressSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar seekBar) { draggingSeek = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                draggingSeek = false;
                Intent seek = playbackIntent(RadioPlaybackService.ACTION_SEEK)
                        .putExtra(RadioPlaybackService.EXTRA_POSITION, seekBar.getProgress());
                startService(seek);
            }
        });
    }

    private void openYandexMusic() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("ru.yandex.music");
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=ru.yandex.music")));
        } catch (Exception ignored) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=ru.yandex.music")));
        }
    }

    private void showVoicePresetDialog() {
        String[] presets = {
                PreferenceStore.VOICE_DUO_BEST,
                PreferenceStore.VOICE_DUO_FAST,
                PreferenceStore.VOICE_MALE,
                PreferenceStore.VOICE_FEMALE,
                PreferenceStore.VOICE_SYSTEM
        };
        String[] labels = new String[presets.length];
        int selected = 0;
        String current = preferenceStore.getVoicePreset();
        for (int i = 0; i < presets.length; i++) {
            labels[i] = PreferenceStore.voicePresetLabel(presets[i]);
            if (presets[i].equals(current)) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Формат и голос")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    preferenceStore.setVoicePreset(presets[which]);
                    renderVoicePreset();
                    sendPlaybackAction(RadioPlaybackService.ACTION_RELOAD_VOICE);
                    dialog.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void renderVoicePreset() {
        if (voicePresetButton != null) {
            voicePresetButton.setText("🎧 "
                    + PreferenceStore.voicePresetLabel(preferenceStore.getVoicePreset()));
        }
    }

    private void sendPlaybackAction(String action) {
        Intent intent = playbackIntent(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private Intent playbackIntent(String action) {
        return new Intent(this, RadioPlaybackService.class).setAction(action);
    }

    private void rateEpisode(int rating) {
        if (currentEpisode == null) return;
        int saved = preferenceStore.rateEpisode(currentEpisode, rating);
        renderRating();
        Toast.makeText(this,
                saved > 0
                        ? "Буду предлагать больше похожего"
                        : saved < 0 ? "Похожих материалов станет меньше" : "Оценка стала нейтральной",
                Toast.LENGTH_SHORT).show();
    }

    private void rateCurrentMaterial(int rating) {
        if (!musicBreak) {
            rateEpisode(rating);
            return;
        }
        if (currentMusicTitle.isEmpty()) {
            Toast.makeText(this, "Трек ещё не определён", Toast.LENGTH_SHORT).show();
            return;
        }
        int saved = preferenceStore.rateMusic(currentMusicTitle, currentMusicArtist, rating);
        renderRating();
        Toast.makeText(this,
                saved > 0
                        ? "Песня добавлена в предпочтения"
                        : saved < 0 ? "Таких песен станет меньше" : "Оценка песни стала нейтральной",
                Toast.LENGTH_SHORT).show();
    }

    private void renderEpisode() {
        if (currentEpisode == null) return;
        categoryText.setText(currentEpisode.getCategory().toUpperCase(Locale.ROOT));
        titleText.setText(currentEpisode.getTitle());
        descriptionText.setText(currentEpisode.getSummary());
        renderRating();
    }

    private void renderRating() {
        int rating = musicBreak
                ? preferenceStore.getMusicRating(currentMusicTitle, currentMusicArtist)
                : currentEpisode == null ? 0 : preferenceStore.getEpisodeRating(currentEpisode);
        likeButton.setText(musicBreak ? "👍 Песня" : "👍 Нравится");
        dislikeButton.setText(musicBreak ? "👎 Песня" : "👎 Не моё");
        likeButton.setAlpha(rating < 0 ? 0.45f : 1f);
        dislikeButton.setAlpha(rating > 0 ? 0.45f : 1f);
    }

    private void renderPlayback(int position, int duration) {
        playButton.setText(playing ? "Ⅱ Пауза" : "▶ Слушать");
        if (musicBreak) {
            if (generationWait) {
                statusText.setText("Делаю рассказ и сверяю факты… Пока послушайте Яндекс Музыку");
            } else {
                int song = Math.min(Math.max(1, musicCompleted + 1), Math.max(1, musicTarget));
                statusText.setText("Музыкальная пауза · песня " + song + " из " + musicTarget
                        + " · «Сначала» вернёт рассказ");
            }
        } else if (recommendationPending) {
            statusText.setText("Ищу новый рассказ и сверяю факты…");
        } else if (playing) {
            statusText.setText("Сейчас в эфире");
        }
        if (!draggingSeek) {
            progressSeek.setMax(Math.max(duration, 1));
            progressSeek.setProgress(Math.min(position, Math.max(duration, 1)));
        }
        positionText.setText(formatTime(position));
        durationText.setText(formatTime(duration));
    }

    private void startVoiceRequest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Что рассказать или какую команду выполнить?")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (Exception ignored) {
            Toast.makeText(this, "На телефоне не найден сервис распознавания речи", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPEECH || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        handleVoice(results.get(0));
    }

    private void handleVoice(String raw) {
        String command = raw.toLowerCase(Locale.ROOT).trim();
        statusText.setText("Распознано: «" + raw + "»");

        if (command.contains("дальше") || command.contains("следующ")) {
            sendPlaybackAction(RadioPlaybackService.ACTION_NEXT);
            return;
        }
        if (command.contains("пауза") || command.equals("стоп") || command.contains("останов")) {
            sendPlaybackAction(RadioPlaybackService.ACTION_PAUSE);
            return;
        }
        if (command.contains("продолж") || command.contains("слушать")) {
            sendPlaybackAction(RadioPlaybackService.ACTION_PLAY);
            return;
        }
        if (command.contains("повтори") || command.contains("сначала")) {
            sendPlaybackAction(RadioPlaybackService.ACTION_REPLAY);
            return;
        }
        if (command.contains("источник") || command.contains("откуда")) {
            showSources();
            return;
        }

        Episode local = findLocalEpisode(command);
        if (local != null) {
            enqueueEpisode(local);
            statusText.setText("Нашёл проверенный рассказ в локальной подборке");
            return;
        }
        requestGeneratedStory(raw);
    }

    private Episode findLocalEpisode(String command) {
        for (Episode episode : DemoEpisodes.all()) {
            String haystack = (episode.getTitle() + " " + episode.getSummary() + " "
                    + TextUtils.join(" ", episode.getTags())).toLowerCase(Locale.ROOT);
            if (command.contains("торревьех") && haystack.contains("торревьех")) return episode;
            if ((command.contains("кондиционер") || command.contains("кэрриер"))
                    && haystack.contains("hvac")) return episode;
            if ((command.contains("батаре") || command.contains("аккумулятор") || command.contains("литий"))
                    && haystack.contains("аккумулятор")) return episode;
        }
        return null;
    }

    private void requestGeneratedStory(String query) {
        statusText.setText("Делаю рассказ: ищу информацию и сверяю источники…");
        sendPlaybackAction(RadioPlaybackService.ACTION_WAIT_FOR_STORY);
        apiClient.requestStory(query, preferenceStore.compactProfile(),
                preferenceStore.getVoicePreset(), new RadioApiClient.Callback() {
            @Override
            public void onSuccess(Episode episode) {
                enqueueEpisode(episode);
                statusText.setText("Рассказ подготовлен по подтверждённым источникам");
            }

            @Override
            public void onError(String message) {
                sendPlaybackAction(RadioPlaybackService.ACTION_CANCEL_WAIT_FOR_STORY);
                statusText.setText("Не удалось подготовить рассказ");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Генерация по запросу недоступна")
                        .setMessage(message + "\n\nБез сервера доступен режим «Системный голос · офлайн».")
                        .setPositiveButton("Понятно", null)
                        .show();
            }
        });
    }

    private void enqueueEpisode(Episode episode) {
        currentEpisode = episode;
        renderEpisode();
        Intent intent = playbackIntent(RadioPlaybackService.ACTION_ENQUEUE)
                .putExtra(RadioPlaybackService.EXTRA_EPISODE, episode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void showSources() {
        if (currentEpisode == null || currentEpisode.getSources().isEmpty()) {
            Toast.makeText(this, "Для материала нет сохранённых источников", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        list.setPadding(padding, padding / 2, padding, padding / 2);
        for (Source source : currentEpisode.getSources()) {
            TextView link = new TextView(this);
            link.setText("• " + source.getTitle() + "\n" + source.getUrl());
            link.setTextColor(Color.rgb(20, 90, 150));
            link.setTextSize(15f);
            link.setPadding(0, dp(10), 0, dp(10));
            link.setOnClickListener(view -> startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(source.getUrl()))));
            list.addView(link);
        }
        new AlertDialog.Builder(this)
                .setTitle("Где перепроверить")
                .setView(list)
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRequest();
        }
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RadioPlaybackService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(playbackReceiver, filter);
        }
        receiverRegistered = true;
        if (hasEnteredForeground && stoppedAt > 0L
                && SystemClock.elapsedRealtime() - stoppedAt >= 1000L) {
            currentEpisode = null;
            renderEpisode();
            sendPlaybackAction(RadioPlaybackService.ACTION_START_NEW_SESSION);
        }
        hasEnteredForeground = true;
        stoppedAt = 0L;
    }

    @Override
    protected void onStop() {
        stoppedAt = SystemClock.elapsedRealtime();
        if (receiverRegistered) {
            unregisterReceiver(playbackReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(
                    accelerometerListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        readMusicTrack();
    }

    @Override
    protected void onPause() {
        if (orientationListener != null) orientationListener.disable();
        if (sensorManager != null) sensorManager.unregisterListener(accelerometerListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (orientationListener != null) orientationListener.disable();
        if (sensorManager != null) sensorManager.unregisterListener(accelerometerListener);
        super.onDestroy();
    }

    /**
     * Samsung can keep a sensor Activity in portrait when the global rotation toggle is locked.
     * FactRadio is an audio app used in a car mount, so follow the physical device position
     * explicitly and support all four orientations regardless of that global toggle.
     */
    private void configurePhysicalOrientation() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager == null
                ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                applyStableOrientation(requestedOrientationForDegrees(orientation));
            }
        };
    }

    private void applyStableOrientation(int target) {
        long now = SystemClock.elapsedRealtime();
        if (target != orientationCandidate) {
            orientationCandidate = target;
            orientationCandidateSince = now;
            return;
        }
        if (now - orientationCandidateSince < 450L
                || getRequestedOrientation() == target) return;
        setRequestedOrientation(target);
    }

    static int requestedOrientationForDegrees(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized < 45 || normalized >= 315) {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        if (normalized < 135) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
        if (normalized < 225) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }

    static int requestedOrientationForGravity(float x, float y) {
        float absoluteX = Math.abs(x);
        float absoluteY = Math.abs(y);
        if (Math.max(absoluteX, absoluteY) < 5.5f
                || Math.abs(absoluteX - absoluteY) < 1.4f) {
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        if (absoluteY > absoluteX) {
            return y >= 0f
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }
        return x >= 0f
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
    }

    private void readMusicTrack() {
        SharedPreferences music = getSharedPreferences(
                YandexNotificationListener.MUSIC_PREFS, MODE_PRIVATE);
        String title = music.getString(YandexNotificationListener.KEY_TITLE, "");
        String artist = music.getString(YandexNotificationListener.KEY_ARTIST, "");
        currentMusicTitle = title == null ? "" : title;
        currentMusicArtist = artist == null ? "" : artist;
        if (title == null || title.isEmpty()) {
            musicTrackText.setText("Трек пока не определён");
            musicArtistText.setText("Включите Яндекс Музыку после выдачи доступа");
        } else {
            musicTrackText.setText(title);
            musicArtistText.setText(artist == null || artist.isEmpty() ? "Яндекс Музыка" : artist);
        }
    }

    private static String formatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
