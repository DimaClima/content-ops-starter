package com.example.factradio.playback;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.media.browse.MediaBrowser;
import android.service.media.MediaBrowserService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.example.factradio.MainActivity;
import com.example.factradio.R;
import com.example.factradio.data.DemoEpisodes;
import com.example.factradio.data.PreferenceStore;
import com.example.factradio.model.Episode;
import com.example.factradio.model.DialogueLine;
import com.example.factradio.music.YandexNotificationListener;
import com.example.factradio.network.PodcastRenderClient;
import com.example.factradio.network.RadioApiClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class RadioPlaybackService extends MediaBrowserService {
    public static final String ACTION_STATE = "com.example.factradio.STATE";
    public static final String ACTION_PLAY = "com.example.factradio.PLAY";
    public static final String ACTION_PAUSE = "com.example.factradio.PAUSE";
    public static final String ACTION_NEXT = "com.example.factradio.NEXT";
    public static final String ACTION_REPLAY = "com.example.factradio.REPLAY";
    public static final String ACTION_SEEK = "com.example.factradio.SEEK";
    public static final String ACTION_ENQUEUE = "com.example.factradio.ENQUEUE";
    public static final String ACTION_LIKE = "com.example.factradio.LIKE";
    public static final String ACTION_DISLIKE = "com.example.factradio.DISLIKE";
    public static final String ACTION_RELOAD_VOICE = "com.example.factradio.RELOAD_VOICE";
    public static final String ACTION_WAIT_FOR_STORY = "com.example.factradio.WAIT_FOR_STORY";
    public static final String ACTION_CANCEL_WAIT_FOR_STORY = "com.example.factradio.CANCEL_WAIT_FOR_STORY";
    public static final String ACTION_START_NEW_SESSION = "com.example.factradio.START_NEW_SESSION";

    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_EPISODE = "episode";
    public static final String EXTRA_MUSIC_BREAK = "music_break";
    public static final String EXTRA_MUSIC_TARGET = "music_target";
    public static final String EXTRA_MUSIC_COMPLETED = "music_completed";
    public static final String EXTRA_GENERATION_WAIT = "generation_wait";
    public static final String EXTRA_RECOMMENDATION_PENDING = "recommendation_pending";

    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "fact_radio_playback";
    private static final String MEDIA_ID_NEW_STORY = "factradio:new";
    private static final String ROOT_ID = "factradio_root";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService cloudAudioExecutor = Executors.newSingleThreadExecutor();
    private ArrayList<Episode> queue;
    private int currentIndex;
    private Episode current;
    private MediaPlayer player;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean pendingAutoplay;
    private String synthesisToken;
    private ArrayList<File> synthesisParts = new ArrayList<>();
    private List<DialogueLine> synthesisDialogue = new ArrayList<>();
    private int synthesisIndex;
    private Voice maleVoice;
    private Voice femaleVoice;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private Object focusRequest;
    private PreferenceStore preferenceStore;
    private PodcastRenderClient renderClient;
    private RadioApiClient storyClient;
    private boolean cloudRenderPending;
    private String cloudRenderToken = "";
    private String cloudDownloadToken = "";
    private boolean musicBreak;
    private int musicTarget;
    private int musicCompleted;
    private String musicTrackIdentity = "";
    private MediaController yandexController;
    private boolean generationWaitMusic;
    private boolean recommendationPending;
    private boolean waitingForFreshStory;
    private final AudioInterruptionPolicy audioInterruptionPolicy =
            new AudioInterruptionPolicy();
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            this::handleAudioFocusChange;

    private final BroadcastReceiver musicTrackReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!musicBreak) return;
            onYandexTrackChanged(
                    intent.getStringExtra(YandexNotificationListener.EXTRA_TITLE),
                    intent.getStringExtra(YandexNotificationListener.EXTRA_ARTIST));
        }
    };

    private final Runnable positionTicker = new Runnable() {
        @Override
        public void run() {
            broadcastState();
            handler.postDelayed(this, 800L);
        }
    };

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void onCreate() {
        super.onCreate();
        preferenceStore = new PreferenceStore(this);
        renderClient = new PodcastRenderClient();
        storyClient = new RadioApiClient();
        queue = new ArrayList<>();
        currentIndex = -1;
        current = null;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        IntentFilter musicFilter = new IntentFilter(YandexNotificationListener.ACTION_TRACK_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(musicTrackReceiver, musicFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(musicTrackReceiver, musicFilter);
        }

        createNotificationChannel();
        configureMediaSession();
        setSessionToken(mediaSession.getSessionToken());
        startForeground(NOTIFICATION_ID, buildNotification(false));
        configureTts();
        loadCloudFeed();
        handler.post(positionTicker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            broadcastState();
            return START_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_PLAY:
                play();
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_NEXT:
                next();
                break;
            case ACTION_REPLAY:
                replay();
                break;
            case ACTION_SEEK:
                seekTo(intent.getIntExtra(EXTRA_POSITION, 0));
                break;
            case ACTION_ENQUEUE:
                Episode added = (Episode) intent.getSerializableExtra(EXTRA_EPISODE);
                if (added != null) enqueueAndPlay(added);
                break;
            case ACTION_LIKE:
                rateCurrent(1);
                break;
            case ACTION_DISLIKE:
                rateCurrent(-1);
                break;
            case ACTION_RELOAD_VOICE:
                reloadVoice();
                break;
            case ACTION_WAIT_FOR_STORY:
                startGenerationWaitMusic();
                break;
            case ACTION_CANCEL_WAIT_FOR_STORY:
                cancelGenerationWaitMusic();
                break;
            case ACTION_START_NEW_SESSION:
                startNewSession(false);
                break;
            default:
                break;
        }
        return START_STICKY;
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        ArrayList<MediaBrowser.MediaItem> items = new ArrayList<>();
        if (ROOT_ID.equals(parentId)) {
            MediaDescription fresh = new MediaDescription.Builder()
                    .setMediaId(MEDIA_ID_NEW_STORY)
                    .setTitle("Новый выпуск")
                    .setSubtitle(recommendationPending
                            ? "ФактРадио уже ищет и проверяет факты"
                            : "Создать свежий документальный рассказ")
                    .setDescription("Новая тема по вашим интересам без повтора недавних историй")
                    .build();
            items.add(new MediaBrowser.MediaItem(fresh, MediaBrowser.MediaItem.FLAG_PLAYABLE));
            for (Episode episode : queue) {
                if (!preferenceStore.canPlayNow(episode)) continue;
                MediaDescription description = new MediaDescription.Builder()
                        .setMediaId(episode.getId())
                        .setTitle(episode.getTitle())
                        .setSubtitle(episode.getCategory())
                        .setDescription(episode.getSummary())
                        .build();
                items.add(new MediaBrowser.MediaItem(
                        description, MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
        }
        result.sendResult(items);
    }

    private void configureTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ttsReady = true;
            tts.setLanguage(new Locale("ru", "RU"));
            chooseRussianVoices();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onError(String utteranceId) {
                    if (utteranceId.startsWith(synthesisToken == null ? "-" : synthesisToken)) {
                        pendingAutoplay = false;
                    }
                }
                @Override public void onDone(String utteranceId) {
                    if (synthesisToken == null || !utteranceId.startsWith(synthesisToken)) return;
                    handler.post(() -> {
                        synthesisIndex++;
                        synthesizeNextDialogueLine();
                    });
                }
            });
            if (pendingAutoplay) synthesizeCurrent();
        });
    }

    private void play() {
        pendingAutoplay = true;
        audioInterruptionPolicy.onUserPlay();
        if (musicBreak) {
            if (yandexController != null) yandexController.getTransportControls().play();
            updateNotification();
            broadcastState();
            return;
        }
        if (player != null) {
            requestAudioFocus();
            player.start();
            updatePlaybackState();
            updateNotification();
            broadcastState();
            return;
        }
        if (current == null) {
            waitingForFreshStory = true;
            requestFreshStory(defaultAutomaticQuery(), true, 0);
            return;
        }
        synthesizeCurrent();
    }

    private void pause() {
        pendingAutoplay = false;
        audioInterruptionPolicy.onUserPauseOrPermanentLoss();
        if (musicBreak && yandexController != null) {
            yandexController.getTransportControls().pause();
        }
        if (player != null && player.isPlaying()) player.pause();
        updatePlaybackState();
        updateNotification();
        broadcastState();
    }

    private void next() {
        if (musicBreak) {
            stopMusicBreak();
        } else if (current != null) {
            preferenceStore.recordSkipped(current);
        }
        advanceStory();
    }

    private void advanceStory() {
        pendingAutoplay = true;
        waitingForFreshStory = true;
        invalidateAudioWork();
        current = null;
        currentIndex = -1;
        releasePlayer();
        requestFreshStory(defaultAutomaticQuery(), true, 0);
        if (preferenceStore.isMusicMixEnabled() && !musicBreak) startGenerationWaitMusic();
        updatePlaybackState();
        updateNotification();
        broadcastState();
    }

    private void startNewSession(boolean autoplay) {
        if (musicBreak) stopMusicBreak();
        if (current != null && isPlaybackActive()) preferenceStore.recordSkipped(current);
        pendingAutoplay = autoplay;
        waitingForFreshStory = true;
        invalidateAudioWork();
        current = null;
        currentIndex = -1;
        releasePlayer();
        requestFreshStory(defaultAutomaticQuery(), autoplay, 0);
        updatePlaybackState();
        updateNotification();
        broadcastState();
    }

    private void replay() {
        if (musicBreak) {
            stopMusicBreak();
            pendingAutoplay = true;
            synthesizeCurrent();
            return;
        }
        pendingAutoplay = true;
        if (player != null) {
            player.seekTo(0);
            requestAudioFocus();
            player.start();
            updatePlaybackState();
            updateNotification();
            return;
        }
        synthesizeCurrent();
    }

    private void seekTo(int positionMs) {
        if (musicBreak && yandexController != null) {
            yandexController.getTransportControls().seekTo(Math.max(0, positionMs));
            broadcastState();
            return;
        }
        if (player == null) return;
        int safe = Math.max(0, Math.min(positionMs, player.getDuration()));
        player.seekTo(safe);
        broadcastState();
    }

    private void enqueueAndPlay(Episode episode) {
        if (musicBreak) stopMusicBreak();
        int insertion = Math.min(currentIndex + 1, queue.size());
        queue.add(insertion, episode);
        currentIndex = insertion;
        current = episode;
        pendingAutoplay = true;
        releasePlayer();
        synthesizeCurrent();
    }

    private void rateCurrent(int value) {
        if (musicBreak) {
            MediaMetadata metadata = yandexController == null ? null : yandexController.getMetadata();
            String title = metadata == null ? ""
                    : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata == null ? ""
                    : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (title != null && !title.trim().isEmpty()) {
                preferenceStore.rateMusic(title, artist == null ? "" : artist, value);
            }
            broadcastState();
            return;
        }
        if (current == null) return;
        preferenceStore.rateEpisode(current, value);
        broadcastState();
    }

    private void synthesizeCurrent() {
        if (!pendingAutoplay || current == null) return;

        releasePlayer();
        String voicePreset = preferenceStore.getVoicePreset();
        if (!PreferenceStore.VOICE_SYSTEM.equals(voicePreset) && !current.getAudioUrl().isEmpty()) {
            prepareRemoteAndPlay(current.getAudioUrl());
            return;
        }
        if (!PreferenceStore.VOICE_SYSTEM.equals(voicePreset)) {
            requestCloudRender(voicePreset);
            return;
        }
        if (!ttsReady) return;

        tts.stop();
        cleanupSynthesisParts();
        synthesisToken = current.getId() + "-" + System.nanoTime();
        synthesisDialogue = current.getDialogue().isEmpty()
                ? java.util.Collections.singletonList(new DialogueLine(DialogueLine.MALE, current.getScript()))
                : current.getDialogue();
        synthesisIndex = 0;
        synthesizeNextDialogueLine();
        updateNotification();
        broadcastState();
    }

    private void requestCloudRender(String voicePreset) {
        if (current == null) return;
        final Episode renderEpisode = current;
        final String renderToken = renderEpisode.getId() + "-" + System.nanoTime();
        cloudRenderToken = renderToken;
        cloudRenderPending = true;
        updateNotification();
        renderClient.render(renderEpisode, voicePreset, new PodcastRenderClient.Callback() {
            @Override public void onSuccess(String audioUrl) {
                if (!renderToken.equals(cloudRenderToken)
                        || current == null
                        || !renderEpisode.getId().equals(current.getId())) return;
                cloudRenderPending = false;
                if (!pendingAutoplay) return;
                current = copyWithAudio(current, audioUrl);
                prepareRemoteAndPlay(audioUrl);
                broadcastState();
            }

            @Override public void onError(String message) {
                if (!renderToken.equals(cloudRenderToken)
                        || current == null
                        || !renderEpisode.getId().equals(current.getId())) return;
                cloudRenderPending = false;
                android.widget.Toast.makeText(
                        RadioPlaybackService.this,
                        message + ". Включаю запасной голос телефона.",
                        android.widget.Toast.LENGTH_LONG
                ).show();
                synthesizeSystemCurrent();
            }
        });
    }

    private void synthesizeSystemCurrent() {
        if (!pendingAutoplay || current == null) return;
        if (!ttsReady) {
            updateNotification();
            broadcastState();
            return;
        }
        releasePlayer();
        tts.stop();
        cleanupSynthesisParts();
        synthesisToken = current.getId() + "-fallback-" + System.nanoTime();
        synthesisDialogue = current.getDialogue().isEmpty()
                ? java.util.Collections.singletonList(
                        new DialogueLine(DialogueLine.MALE, current.getScript()))
                : current.getDialogue();
        synthesisIndex = 0;
        synthesizeNextDialogueLine();
        updateNotification();
        broadcastState();
    }

    private Episode copyWithAudio(Episode source, String audioUrl) {
        return new Episode(
                source.getId(), source.getCategory(), source.getTitle(), source.getSummary(),
                source.getScript(), audioUrl, source.getDialogue(), source.getTags(), source.getSources());
    }

    private void reloadVoice() {
        if (current == null) return;
        pendingAutoplay = true;
        invalidateAudioWork();
        current = copyWithAudio(current, "");
        releasePlayer();
        synthesizeCurrent();
    }

    private void invalidateAudioWork() {
        cloudRenderToken = "";
        cloudDownloadToken = "";
        cloudRenderPending = false;
        synthesisToken = null;
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
        }
        cleanupSynthesisParts();
    }

    private void synthesizeNextDialogueLine() {
        if (!pendingAutoplay || synthesisToken == null) return;
        if (synthesisIndex >= synthesisDialogue.size()) {
            File target = episodeAudioFile(synthesisToken);
            try {
                WaveJoiner.join(synthesisParts, target);
                cleanupSynthesisParts();
                prepareAndPlay(target);
            } catch (Exception ignored) {
                pendingAutoplay = false;
                cleanupSynthesisParts();
            }
            return;
        }

        DialogueLine line = synthesisDialogue.get(synthesisIndex);
        boolean female = DialogueLine.FEMALE.equals(line.getSpeaker());
        Voice selected = female ? femaleVoice : maleVoice;
        if (selected != null) tts.setVoice(selected);
        tts.setPitch(female ? 1.04f : 0.96f);
        tts.setSpeechRate(female ? 1.01f : 0.97f);

        String utteranceId = synthesisToken + "-part-" + synthesisIndex;
        File part = new File(getCacheDir(), utteranceId + ".wav");
        synthesisParts.add(part);
        Bundle parameters = new Bundle();
        parameters.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
        int result = tts.synthesizeToFile(line.getText(), parameters, part, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            pendingAutoplay = false;
            cleanupSynthesisParts();
        }
    }

    private void chooseRussianVoices() {
        Set<Voice> available = tts.getVoices();
        if (available == null) return;
        ArrayList<Voice> russian = new ArrayList<>();
        for (Voice voice : available) {
            if (voice.getLocale() != null && "ru".equals(voice.getLocale().getLanguage())) {
                russian.add(voice);
            }
        }
        Collections.sort(russian, (left, right) -> {
            int quality = Integer.compare(right.getQuality(), left.getQuality());
            if (quality != 0) return quality;
            int network = Integer.compare(
                    left.isNetworkConnectionRequired() ? 0 : 1,
                    right.isNetworkConnectionRequired() ? 0 : 1);
            if (network != 0) return network;
            return left.getName().compareTo(right.getName());
        });
        if (!russian.isEmpty()) maleVoice = russian.get(0);
        if (russian.size() > 1) femaleVoice = russian.get(1);
        else femaleVoice = maleVoice;
    }

    private void prepareRemoteAndPlay(String url) {
        if (current == null || url == null || url.trim().isEmpty()) return;
        releasePlayer();
        String episodeId = current.getId();
        String token = episodeId + "-" + System.nanoTime();
        cloudDownloadToken = token;
        String safeId = episodeId.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(getCacheDir(), "cloud-" + safeId + ".wav");
        if (!target.isFile() || target.length() <= 44L) {
            android.widget.Toast.makeText(
                    this,
                    "Загружаю облачный голос…",
                    android.widget.Toast.LENGTH_SHORT
            ).show();
        }
        updateNotification();
        broadcastState();

        cloudAudioExecutor.execute(() -> {
            try {
                File downloaded = CloudAudioDownloader.download(url, target);
                handler.post(() -> {
                    if (!pendingAutoplay || current == null
                            || !episodeId.equals(current.getId())
                            || !token.equals(cloudDownloadToken)) return;
                    prepareAndPlay(downloaded);
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (!token.equals(cloudDownloadToken)) return;
                    if (current == null || !episodeId.equals(current.getId())) return;
                    String detail = error.getMessage();
                    android.widget.Toast.makeText(
                            RadioPlaybackService.this,
                            detail == null || detail.trim().isEmpty()
                                    ? "Не удалось скачать облачный голос"
                                    : "Не удалось скачать облачный голос: " + detail,
                            android.widget.Toast.LENGTH_LONG
                    ).show();
                    current = copyWithAudio(current, "");
                    requestCloudRender(preferenceStore.getVoicePreset());
                });
            }
        });
    }

    private File episodeAudioFile(String utteranceId) {
        return new File(getCacheDir(), utteranceId + ".wav");
    }

    private void prepareAndPlay(File file) {
        if (!pendingAutoplay || !file.exists()) return;
        try {
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(ignored -> onStoryCompleted());
            player.setOnErrorListener((ignored, what, extra) -> {
                next();
                return true;
            });
            player.prepare();
            requestAudioFocus();
            preferenceStore.recordStarted(current);
            player.start();
            mediaSession.setActive(true);
            updatePlaybackState();
            updateNotification();
            broadcastState();
        } catch (Exception ignored) {
            releasePlayer();
        }
    }

    private void onStoryCompleted() {
        preferenceStore.recordCompleted(current);
        releasePlayer();
        abandonAudioFocus();
        if (preferenceStore.isMusicMixEnabled() && startMusicBreak()) return;
        advanceStory();
    }

    private boolean startMusicBreak() {
        yandexController = findYandexController();
        if (yandexController == null) {
            android.widget.Toast.makeText(
                    this,
                    "Откройте Яндекс Музыку, выберите плейлист и разрешите определение трека",
                    android.widget.Toast.LENGTH_LONG
            ).show();
            return false;
        }

        musicBreak = true;
        pendingAutoplay = true;
        musicTarget = ThreadLocalRandom.current().nextInt(1, 4);
        musicCompleted = 0;
        musicTrackIdentity = controllerTrackIdentity(yandexController);
        yandexController.getTransportControls().play();
        updatePlaybackState();
        updateNotification();
        broadcastState();
        return true;
    }

    private void startGenerationWaitMusic() {
        if (musicBreak && generationWaitMusic) return;
        if (musicBreak) stopMusicBreak();
        generationWaitMusic = true;
        if (!startMusicBreak()) {
            generationWaitMusic = false;
            broadcastState();
            return;
        }
        musicTarget = 1;
        updateNotification();
        broadcastState();
    }

    private void cancelGenerationWaitMusic() {
        if (!generationWaitMusic) return;
        stopMusicBreak();
    }

    private void onYandexTrackChanged(String title, String artist) {
        String identity = ((title == null ? "" : title.trim()) + "|"
                + (artist == null ? "" : artist.trim())).toLowerCase(Locale.ROOT);
        if (identity.equals("|") || identity.equals(musicTrackIdentity)) return;
        if (musicTrackIdentity.isEmpty()) {
            musicTrackIdentity = identity;
            updateNotification();
            broadcastState();
            return;
        }

        musicCompleted++;
        if (generationWaitMusic) {
            musicTrackIdentity = identity;
            musicTarget = Math.max(1, musicCompleted + 1);
            updateNotification();
            broadcastState();
            return;
        }
        if (musicCompleted >= musicTarget) {
            stopMusicBreak();
            handler.postDelayed(this::advanceStory, 350L);
            return;
        }
        musicTrackIdentity = identity;
        updateNotification();
        broadcastState();
    }

    private void stopMusicBreak() {
        if (yandexController != null) {
            try {
                yandexController.getTransportControls().pause();
            } catch (Exception ignored) {}
        }
        musicBreak = false;
        generationWaitMusic = false;
        musicTarget = 0;
        musicCompleted = 0;
        musicTrackIdentity = "";
        yandexController = null;
        updatePlaybackState();
        updateNotification();
        broadcastState();
    }

    private MediaController findYandexController() {
        try {
            MediaSessionManager manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName listener = new ComponentName(this, YandexNotificationListener.class);
            for (MediaController controller : manager.getActiveSessions(listener)) {
                String packageName = controller.getPackageName();
                if (packageName == null) continue;
                String normalized = packageName.toLowerCase(Locale.ROOT);
                if (normalized.equals("ru.yandex.music")
                        || (normalized.contains("yandex") && normalized.contains("music"))) {
                    return controller;
                }
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return null;
    }

    private String controllerTrackIdentity(MediaController controller) {
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        if (metadata == null) return "";
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return ((title == null ? "" : title.trim()) + "|"
                + (artist == null ? "" : artist.trim())).toLowerCase(Locale.ROOT);
    }

    private void configureMediaSession() {
        mediaSession = new MediaSession(this, "FactRadioSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
            @Override public void onRewind() { replay(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (mediaId == null) return;
                if (MEDIA_ID_NEW_STORY.equals(mediaId)) {
                    startNewSession(true);
                    return;
                }
                for (int index = 0; index < queue.size(); index++) {
                    if (!mediaId.equals(queue.get(index).getId())) continue;
                    currentIndex = index;
                    current = queue.get(index);
                    pendingAutoplay = true;
                    releasePlayer();
                    synthesizeCurrent();
                    return;
                }
            }
            @Override public void onPlayFromSearch(String query, Bundle extras) {
                String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    pendingAutoplay = true;
                    waitingForFreshStory = true;
                    current = null;
                    currentIndex = -1;
                    releasePlayer();
                    requestFreshStory(query.trim(), true, 0);
                    return;
                }
                for (int index = 0; index < queue.size(); index++) {
                    Episode episode = queue.get(index);
                    if (!normalized.isEmpty()
                            && !episode.getTitle().toLowerCase(Locale.ROOT).contains(normalized)
                            && !episode.getCategory().toLowerCase(Locale.ROOT).contains(normalized)) {
                        continue;
                    }
                    currentIndex = index;
                    current = episode;
                    pendingAutoplay = true;
                    releasePlayer();
                    synthesizeCurrent();
                    return;
                }
                startNewSession(true);
            }
        });
        mediaSession.setActive(true);
        updatePlaybackState();
    }

    private void updatePlaybackState() {
        boolean playing = isPlaybackActive();
        long position = currentPosition();
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_REWIND;
        int stateCode = recommendationPending || waitingForFreshStory
                ? PlaybackState.STATE_BUFFERING
                : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState state = new PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, position, playing ? 1f : 0f)
                .build();
        mediaSession.setPlaybackState(state);
        updateMediaMetadata();
    }

    private void updateMediaMetadata() {
        if (mediaSession == null) return;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        if (musicBreak && yandexController != null && yandexController.getMetadata() != null) {
            MediaMetadata yandex = yandexController.getMetadata();
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE,
                    yandex.getString(MediaMetadata.METADATA_KEY_TITLE));
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST,
                    yandex.getString(MediaMetadata.METADATA_KEY_ARTIST));
            metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, "Яндекс Музыка · ФактРадио");
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION,
                    yandex.getLong(MediaMetadata.METADATA_KEY_DURATION));
        } else if (current != null) {
            metadata.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, current.getId());
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE, current.getTitle());
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, "ФактРадио");
            metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, current.getCategory());
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, currentDuration());
        } else {
            metadata.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, MEDIA_ID_NEW_STORY);
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE,
                    recommendationPending || waitingForFreshStory
                            ? "Готовлю новый выпуск…" : "Новый выпуск");
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, "ФактРадио");
        }
        mediaSession.setMetadata(metadata.build());
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = Api26AudioFocus.create(focusChangeListener);
            }
            Api26AudioFocus.request(audioManager, focusRequest);
        } else {
            audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            Api26AudioFocus.abandon(audioManager, focusRequest);
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }

    private void handleAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            audioInterruptionPolicy.onUserPauseOrPermanentLoss();
            pendingAutoplay = false;
            if (player != null) {
                try { player.pause(); } catch (IllegalStateException ignored) {}
            }
        } else if (change < 0) {
            boolean currentlyPlaying = false;
            if (player != null) {
                try { currentlyPlaying = player.isPlaying(); }
                catch (IllegalStateException ignored) {}
            }
            if (player != null && audioInterruptionPolicy.onTransientLoss(
                    pendingAutoplay, currentlyPlaying)) {
                try { player.pause(); } catch (IllegalStateException ignored) {}
            }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN
                && audioInterruptionPolicy.onFocusGain(pendingAutoplay)
                && player != null) {
            try { player.start(); } catch (IllegalStateException ignored) {}
        }
        updatePlaybackState();
        updateNotification();
        broadcastState();
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Эфир ФактРадио",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Управление воспроизведением документальных историй");
        notificationManager().createNotificationChannel(channel);
    }

    private Notification buildNotification(boolean playing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action replay = action(android.R.drawable.ic_media_previous, "Сначала", ACTION_REPLAY, 2);
        Notification.Action playPause = action(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Пауза" : "Слушать",
                playing ? ACTION_PAUSE : ACTION_PLAY,
                3
        );
        Notification.Action next = action(android.R.drawable.ic_media_next, "Дальше", ACTION_NEXT, 4);
        Notification.Action like = action(android.R.drawable.arrow_up_float, "Нравится", ACTION_LIKE, 5);
        Notification.Action dislike = action(android.R.drawable.arrow_down_float, "Не моё", ACTION_DISLIKE, 6);

        Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        return builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(musicBreak
                        ? "Музыкальная пауза"
                        : current == null ? "ФактРадио" : current.getTitle())
                .setContentText(musicBreak
                        ? "Яндекс Музыка · песня " + currentMusicNumber() + " из " + musicTarget
                        : current == null ? "Эфир готов" : current.getCategory())
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(replay)
                .addAction(playPause)
                .addAction(next)
                .addAction(like)
                .addAction(dislike)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification.Action action(int icon, String title, String action, int requestCode) {
        Intent intent = new Intent(this, RadioPlaybackService.class).setAction(action);
        PendingIntent pending = PendingIntent.getService(
                this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(icon, title, pending).build();
    }

    private void updateNotification() {
        boolean playing = isPlaybackActive();
        notificationManager().notify(NOTIFICATION_ID, buildNotification(playing));
    }

    private void broadcastState() {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_EPISODE, current);
        state.putExtra(EXTRA_PLAYING, isPlaybackActive());
        state.putExtra(EXTRA_POSITION, currentPosition());
        state.putExtra(EXTRA_DURATION, currentDuration());
        state.putExtra(EXTRA_MUSIC_BREAK, musicBreak);
        state.putExtra(EXTRA_MUSIC_TARGET, musicTarget);
        state.putExtra(EXTRA_MUSIC_COMPLETED, musicCompleted);
        state.putExtra(EXTRA_GENERATION_WAIT, generationWaitMusic);
        state.putExtra(EXTRA_RECOMMENDATION_PENDING,
                recommendationPending || waitingForFreshStory);
        sendBroadcast(state);
    }

    private void loadCloudFeed() {
        storyClient.requestFeed(40, new RadioApiClient.FeedCallback() {
            @Override public void onSuccess(ArrayList<Episode> episodes) {
                for (int index = episodes.size() - 1; index >= 0; index--) {
                    if (!preferenceStore.canPlayNow(episodes.get(index))) episodes.remove(index);
                }
                Collections.shuffle(episodes);
                Collections.sort(episodes, (left, right) -> Integer.compare(
                        preferenceStore.score(right), preferenceStore.score(left)));
                for (Episode episode : episodes) {
                    if (containsEpisode(episode.getId())) continue;
                    queue.add(episode);
                }
                // Built-in stories are offline fallbacks only. They are appended
                // after cloud items and never selected as the new session opener.
                for (Episode episode : DemoEpisodes.personalized(RadioPlaybackService.this)) {
                    if (!containsEpisode(episode.getId())) queue.add(episode);
                }
                notifyChildrenChanged(ROOT_ID);
                updatePlaybackState();
                broadcastState();
            }

            @Override public void onError(String message) {
                for (Episode episode : DemoEpisodes.personalized(RadioPlaybackService.this)) {
                    if (!containsEpisode(episode.getId())) queue.add(episode);
                }
                notifyChildrenChanged(ROOT_ID);
            }
        });
    }

    private void requestPersonalizedRecommendation() {
        requestFreshStory(defaultAutomaticQuery(), false, 0);
    }

    private void requestPersonalizedRecommendation(boolean queueIsEmpty) {
        requestFreshStory(defaultAutomaticQuery(), queueIsEmpty, 0);
    }

    private String defaultAutomaticQuery() {
        return "Подбери совершенно новый документальный выпуск по моим интересам: HVAC, "
                + "энергетика и технологии, новости Испании, мировые новости, история мест, "
                + "история изобретений и биографии, Аликанте и Торревьеха, наука и открытия. "
                + "Выбери тему самостоятельно и не повторяй недавние выпуски.";
    }

    private void requestFreshStory(String query, boolean autoplay, int attempt) {
        pendingAutoplay = pendingAutoplay || autoplay;
        waitingForFreshStory = true;
        if (recommendationPending) {
            updatePlaybackState();
            broadcastState();
            return;
        }
        if (!storyClient.isConfigured() || !preferenceStore.reserveAutomaticRecommendation(true)) {
            useOfflineFallback("Сервер нового выпуска не настроен");
            return;
        }
        recommendationPending = true;
        storyClient.requestStory(query, preferenceStore.compactProfile(),
                preferenceStore.getVoicePreset(), UUID.randomUUID().toString(),
                preferenceStore.getRecentTitles(), new RadioApiClient.Callback() {
            @Override public void onSuccess(Episode episode) {
                recommendationPending = false;
                if (containsEpisode(episode.getId()) || !preferenceStore.canPlayNow(episode)) {
                    if (attempt < 1) requestFreshStory(query, autoplay, attempt + 1);
                    else useOfflineFallback("Сервер дважды предложил недавнюю историю");
                    return;
                }
                queue.add(0, episode);
                currentIndex = 0;
                current = episode;
                waitingForFreshStory = false;
                if (generationWaitMusic) stopMusicBreak();
                if (pendingAutoplay) synthesizeCurrent();
                notifyChildrenChanged(ROOT_ID);
                updatePlaybackState();
                updateNotification();
                broadcastState();
            }

            @Override public void onError(String message) {
                recommendationPending = false;
                if (attempt < 1) {
                    requestFreshStory(query, autoplay, attempt + 1);
                    return;
                }
                useOfflineFallback(message);
            }
        });
        updatePlaybackState();
        updateNotification();
        notifyChildrenChanged(ROOT_ID);
        broadcastState();
    }

    private void useOfflineFallback(String message) {
        recommendationPending = false;
        waitingForFreshStory = false;
        if (generationWaitMusic) stopMusicBreak();
        Episode fallback = null;
        for (Episode episode : queue) {
            if (preferenceStore.canPlayNow(episode)) {
                fallback = episode;
                break;
            }
        }
        if (fallback == null) {
            for (Episode episode : DemoEpisodes.personalized(this)) {
                if (preferenceStore.canPlayNow(episode)) {
                    fallback = episode;
                    break;
                }
            }
        }
        if (fallback != null) {
            current = fallback;
            currentIndex = queue.indexOf(fallback);
            if (currentIndex < 0) {
                queue.add(0, fallback);
                currentIndex = 0;
            }
            android.widget.Toast.makeText(this,
                    "Новый выпуск временно недоступен. Включаю неповторённый запасной.",
                    android.widget.Toast.LENGTH_LONG).show();
            if (pendingAutoplay) synthesizeCurrent();
        } else {
            pendingAutoplay = false;
            PlaybackState errorState = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_SKIP_TO_NEXT)
                    .setErrorMessage(message == null ? "Не удалось создать новый выпуск" : message)
                    .setState(PlaybackState.STATE_ERROR, 0, 0f)
                    .build();
            mediaSession.setPlaybackState(errorState);
        }
        notifyChildrenChanged(ROOT_ID);
        updateNotification();
        broadcastState();
    }

    private boolean containsEpisode(String episodeId) {
        if (episodeId == null || episodeId.isEmpty()) return false;
        for (Episode episode : queue) {
            if (episodeId.equals(episode.getId())) return true;
        }
        return false;
    }

    private boolean isPlaybackActive() {
        if (!musicBreak) return player != null && player.isPlaying();
        PlaybackState state = yandexController == null ? null : yandexController.getPlaybackState();
        if (state == null) return false;
        return state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING
                || state.getState() == PlaybackState.STATE_CONNECTING;
    }

    private int currentPosition() {
        if (!musicBreak) {
            try {
                return player == null ? 0 : player.getCurrentPosition();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
        PlaybackState state = yandexController == null ? null : yandexController.getPlaybackState();
        if (state == null) return 0;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, position));
    }

    private int currentDuration() {
        if (!musicBreak) {
            try {
                return player == null ? 0 : player.getDuration();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
        MediaMetadata metadata = yandexController == null ? null : yandexController.getMetadata();
        long duration = metadata == null ? 0L
                : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, duration));
    }

    private int currentMusicNumber() {
        return Math.min(Math.max(1, musicCompleted + 1), Math.max(1, musicTarget));
    }

    private void releasePlayer() {
        if (player == null) return;
        try { player.stop(); } catch (Exception ignored) {}
        player.release();
        player = null;
        updatePlaybackState();
    }

    private void cleanupSynthesisParts() {
        for (File part : synthesisParts) {
            if (part.exists()) part.delete();
        }
        synthesisParts.clear();
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @android.annotation.TargetApi(26)
    private static final class Api26AudioFocus {
        static Object create(AudioManager.OnAudioFocusChangeListener listener) {
            return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build();
        }

        static void request(AudioManager manager, Object request) {
            manager.requestAudioFocus((AudioFocusRequest) request);
        }

        static void abandon(AudioManager manager, Object request) {
            manager.abandonAudioFocusRequest((AudioFocusRequest) request);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(positionTicker);
        if (musicBreak) stopMusicBreak();
        try { unregisterReceiver(musicTrackReceiver); } catch (IllegalArgumentException ignored) {}
        releasePlayer();
        abandonAudioFocus();
        cleanupSynthesisParts();
        cloudAudioExecutor.shutdownNow();
        if (tts != null) tts.shutdown();
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }

}
