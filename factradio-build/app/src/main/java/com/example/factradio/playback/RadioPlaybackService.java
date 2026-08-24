package com.example.factradio.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowserService;
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
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_EPISODE = "episode";
    public static final String EXTRA_MUSIC_BREAK = "music_break";
    public static final String EXTRA_MUSIC_TARGET = "music_target";
    public static final String EXTRA_MUSIC_COMPLETED = "music_completed";
    public static final String EXTRA_GENERATION_WAIT = "generation_wait";

    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "fact_radio_playback";

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
    private AudioFocusRequest focusRequest;
    private PreferenceStore preferenceStore;
    private PodcastRenderClient renderClient;
    private RadioApiClient storyClient;
    private boolean cloudRenderPending;
    private String cloudDownloadToken = "";
    private boolean musicBreak;
    private int musicTarget;
    private int musicCompleted;
    private String musicTrackIdentity = "";
    private MediaController yandexController;
    private boolean generationWaitMusic;
    private boolean recommendationPending;

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
    public void onCreate() {
        super.onCreate();
        preferenceStore = new PreferenceStore(this);
        renderClient = new PodcastRenderClient();
        storyClient = new RadioApiClient();
        queue = DemoEpisodes.personalized(this);
        currentIndex = 0;
        current = queue.isEmpty() ? null : queue.get(0);
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
            default:
                break;
        }
        return START_STICKY;
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("factradio_root", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        ArrayList<MediaBrowser.MediaItem> items = new ArrayList<>();
        if ("factradio_root".equals(parentId)) {
            for (Episode episode : queue) {
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
        synthesizeCurrent();
    }

    private void pause() {
        pendingAutoplay = false;
        if (musicBreak && yandexController != null) {
            yandexController.getTransportControls().pause();
        }
        if (player != null && player.isPlaying()) player.pause();
        updatePlaybackState();
        updateNotification();
        broadcastState();
    }

    private void next() {
        if (queue.isEmpty()) return;
        if (musicBreak) {
            stopMusicBreak();
        } else {
            preferenceStore.recordSkipped(current);
        }
        advanceStory();
    }

    private void advanceStory() {
        if (queue.isEmpty() || currentIndex + 1 >= queue.size()) {
            pendingAutoplay = false;
            releasePlayer();
            requestPersonalizedRecommendation();
            updatePlaybackState();
            updateNotification();
            broadcastState();
            return;
        }
        pendingAutoplay = true;
        currentIndex++;
        current = queue.get(currentIndex);
        releasePlayer();
        synthesizeCurrent();
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
        if (cloudRenderPending) return;
        cloudRenderPending = true;
        updateNotification();
        renderClient.render(current, voicePreset, new PodcastRenderClient.Callback() {
            @Override public void onSuccess(String audioUrl) {
                cloudRenderPending = false;
                if (!pendingAutoplay || current == null) return;
                current = copyWithAudio(current, audioUrl);
                prepareRemoteAndPlay(audioUrl);
                broadcastState();
            }

            @Override public void onError(String message) {
                cloudRenderPending = false;
                pendingAutoplay = false;
                android.widget.Toast.makeText(
                        RadioPlaybackService.this,
                        message + ". Выберите «Системный голос · офлайн».",
                        android.widget.Toast.LENGTH_LONG
                ).show();
                updateNotification();
                broadcastState();
            }
        });
    }

    private Episode copyWithAudio(Episode source, String audioUrl) {
        return new Episode(
                source.getId(), source.getCategory(), source.getTitle(), source.getSummary(),
                source.getScript(), audioUrl, source.getDialogue(), source.getTags(), source.getSources());
    }

    private void reloadVoice() {
        if (current == null) return;
        pendingAutoplay = true;
        cloudRenderPending = false;
        current = copyWithAudio(current, "");
        releasePlayer();
        synthesizeCurrent();
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
        russian.sort(Comparator
                .comparingInt(Voice::getQuality).reversed()
                .thenComparing(voice -> voice.isNetworkConnectionRequired() ? 0 : 1)
                .thenComparing(Voice::getName));
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
                    pendingAutoplay = false;
                    String detail = error.getMessage();
                    android.widget.Toast.makeText(
                            RadioPlaybackService.this,
                            detail == null || detail.trim().isEmpty()
                                    ? "Не удалось скачать облачный голос"
                                    : "Не удалось скачать облачный голос: " + detail,
                            android.widget.Toast.LENGTH_LONG
                    ).show();
                    updateNotification();
                    broadcastState();
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
        requestPersonalizedRecommendation();
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
                if (queue.isEmpty()) return;
                String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
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
        PlaybackState state = new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position, playing ? 1f : 0f)
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
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE, "ФактРадио");
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, "Новые выпуски готовятся");
        }
        mediaSession.setMetadata(metadata.build());
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(change -> {
                            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                                pause();
                                return;
                            }
                            if (change < 0) {
                                // Navigation prompts cause only a temporary pause. Do not clear
                                // pendingAutoplay, so AUDIOFOCUS_GAIN can continue the same story.
                                if (player != null) {
                                    try {
                                        player.pause();
                                    } catch (IllegalStateException ignored) {}
                                }
                                updatePlaybackState();
                                updateNotification();
                                broadcastState();
                                return;
                            }
                            if (change == AudioManager.AUDIOFOCUS_GAIN
                                    && pendingAutoplay && player != null) {
                                try {
                                    player.start();
                                } catch (IllegalStateException ignored) {}
                                updatePlaybackState();
                                updateNotification();
                                broadcastState();
                            }
                        })
                        .build();
            }
            audioManager.requestAudioFocus(focusRequest);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null || focusRequest == null || android.os.Build.VERSION.SDK_INT < 26) return;
        audioManager.abandonAudioFocusRequest(focusRequest);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Эфир ФактРадио",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Управление воспроизведением документальных историй");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
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

        return new Notification.Builder(this, CHANNEL_ID)
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
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(playing));
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
        sendBroadcast(state);
    }

    private void loadCloudFeed() {
        storyClient.requestFeed(40, new RadioApiClient.FeedCallback() {
            @Override public void onSuccess(ArrayList<Episode> episodes) {
                episodes.removeIf(episode -> !preferenceStore.canPlayNow(episode));
                Collections.shuffle(episodes);
                episodes.sort(Comparator.comparingInt(preferenceStore::score).reversed());
                int insertion = Math.min(currentIndex + 1, queue.size());
                for (Episode episode : episodes) {
                    if (containsEpisode(episode.getId())) continue;
                    queue.add(insertion++, episode);
                }
                if (current == null && !queue.isEmpty()) {
                    currentIndex = 0;
                    current = queue.get(0);
                }
                notifyChildrenChanged("factradio_root");
                updatePlaybackState();
                broadcastState();
            }

            @Override public void onError(String message) {
                // Встроенные выпуски остаются доступными без облачной базы.
            }
        });
    }

    private void requestPersonalizedRecommendation() {
        if (recommendationPending || !storyClient.isConfigured()
                || !preferenceStore.reserveAutomaticRecommendation()) return;
        recommendationPending = true;
        String query = "Подбери новый документальный выпуск по моим интересам: HVAC, энергетика и технологии, "
                + "новости Испании, мировые новости, история мест, история изобретений и биографии, "
                + "Аликанте и Торревьеха, наука и открытия. Не повторяй недавние темы.";
        storyClient.requestStory(query, preferenceStore.compactProfile(),
                preferenceStore.getVoicePreset(), new RadioApiClient.Callback() {
            @Override public void onSuccess(Episode episode) {
                recommendationPending = false;
                if (containsEpisode(episode.getId()) || !preferenceStore.canPlayNow(episode)) return;
                int insertion = Math.min(currentIndex + 1, queue.size());
                queue.add(insertion, episode);
                notifyChildrenChanged("factradio_root");
                broadcastState();
            }

            @Override public void onError(String message) {
                recommendationPending = false;
            }
        });
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
