package com.example.factradio.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.factradio.model.Episode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class PreferenceStore {
    public static final String VOICE_DUO_BEST = "duo_best";
    public static final String VOICE_DUO_FAST = "duo_fast";
    public static final String VOICE_MALE = "male";
    public static final String VOICE_FEMALE = "female";
    public static final String VOICE_SYSTEM = "system";

    private static final String FILE = "fact_radio_preferences";
    private static final String TAG_PREFIX = "tag_score_";
    private static final String EPISODE_PREFIX = "episode_rating_";
    private static final String LAST_PLAYED_PREFIX = "episode_last_played_";
    private static final String TITLE_RATING_PREFIX = "title_rating_";
    private static final String TITLE_LAST_PLAYED_PREFIX = "title_last_played_";
    private static final String MUSIC_PREFIX = "music_rating_";
    private static final String COMPLETED_PREFIX = "completed_tag_";
    private static final String SKIPPED_PREFIX = "skipped_tag_";
    private static final String RECENT_TITLES = "recent_episode_titles";
    private static final String HISTORY_MIGRATED_AT = "history_migrated_at";
    private static final String COMPLETED_TOTAL = "completed_episode_total";
    private static final String VOICE_PRESET = "voice_preset";
    private static final String MUSIC_MIX_ENABLED = "music_mix_enabled";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long NEUTRAL_REPEAT_DELAY_MS = 30L * DAY_MS;
    private static final long LIKED_REPEAT_DELAY_MS = 7L * DAY_MS;

    private final SharedPreferences preferences;

    public PreferenceStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String recent = preferences.getString(RECENT_TITLES, "");
        if (preferences.getLong(HISTORY_MIGRATED_AT, 0L) == 0L
                && recent != null && !recent.trim().isEmpty()) {
            preferences.edit().putLong(HISTORY_MIGRATED_AT, System.currentTimeMillis()).apply();
        }
    }

    public int rateEpisode(Episode episode, int value) {
        int requested = Integer.compare(value, 0);
        int previous = getEpisodeRating(episode);
        int normalized = previous == requested ? 0 : requested;
        int delta = normalized - previous;
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(EPISODE_PREFIX + episode.getId(), normalized);
        editor.putInt(TITLE_RATING_PREFIX + titleKey(episode.getTitle()), normalized);

        for (String tag : episode.getTags()) {
            String key = TAG_PREFIX + normalize(tag);
            int next = clamp(preferences.getInt(key, 0) + delta * 2, -20, 20);
            editor.putInt(key, next);
        }
        editor.apply();
        return normalized;
    }

    public int getEpisodeRating(Episode episode) {
        String ratingKey = TITLE_RATING_PREFIX + titleKey(episode.getTitle());
        if (preferences.contains(ratingKey)) return preferences.getInt(ratingKey, 0);
        return preferences.getInt(EPISODE_PREFIX + episode.getId(), 0);
    }

    public int score(Episode episode) {
        int score = 0;
        for (String tag : episode.getTags()) {
            score += preferences.getInt(TAG_PREFIX + normalize(tag), 0);
        }
        return score;
    }

    public long getLastPlayedAt(Episode episode) {
        if (episode == null) return 0L;
        long saved = preferences.getLong(LAST_PLAYED_PREFIX + episode.getId(), 0L);
        if (saved > 0L) return saved;
        saved = preferences.getLong(TITLE_LAST_PLAYED_PREFIX + titleKey(episode.getTitle()), 0L);
        if (saved > 0L) return saved;
        return wasInLegacyRecentList(episode.getTitle())
                ? preferences.getLong(HISTORY_MIGRATED_AT, 0L)
                : 0L;
    }

    public boolean canPlayNow(Episode episode) {
        if (episode == null) return false;
        int rating = getEpisodeRating(episode);
        if (rating < 0) return false;
        long lastPlayed = getLastPlayedAt(episode);
        if (lastPlayed == 0L) return true;
        long delay = rating > 0 ? LIKED_REPEAT_DELAY_MS : NEUTRAL_REPEAT_DELAY_MS;
        return System.currentTimeMillis() - lastPlayed >= delay;
    }

    public int rateMusic(String title, String artist, int value) {
        String identity = normalize(title + "|" + artist);
        String key = MUSIC_PREFIX + identity.hashCode();
        int requested = Integer.compare(value, 0);
        int previous = preferences.getInt(key, 0);
        int normalized = previous == requested ? 0 : requested;
        preferences.edit()
                .putInt(key, normalized)
                .apply();
        return normalized;
    }

    public int getMusicRating(String title, String artist) {
        String identity = normalize(title + "|" + artist);
        return preferences.getInt(MUSIC_PREFIX + identity.hashCode(), 0);
    }

    public void recordCompleted(Episode episode) {
        if (episode == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (String tag : episode.getTags()) {
            String key = COMPLETED_PREFIX + normalize(tag);
            editor.putInt(key, clamp(preferences.getInt(key, 0) + 1, 0, 50));
        }
        rememberTitle(editor, episode.getTitle());
        editor.putLong(LAST_PLAYED_PREFIX + episode.getId(), System.currentTimeMillis());
        editor.putLong(TITLE_LAST_PLAYED_PREFIX + titleKey(episode.getTitle()), System.currentTimeMillis());
        editor.putInt(COMPLETED_TOTAL, preferences.getInt(COMPLETED_TOTAL, 0) + 1);
        editor.apply();
    }

    /** Mark the episode as heard as soon as real playback starts, not only at completion. */
    public void recordStarted(Episode episode) {
        if (episode == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        rememberTitle(editor, episode.getTitle());
        editor.putLong(LAST_PLAYED_PREFIX + episode.getId(), System.currentTimeMillis());
        editor.putLong(TITLE_LAST_PLAYED_PREFIX + titleKey(episode.getTitle()), System.currentTimeMillis());
        editor.apply();
    }

    public boolean reserveAutomaticRecommendation() {
        return reserveAutomaticRecommendation(false);
    }

    public boolean reserveAutomaticRecommendation(boolean queueIsEmpty) {
        // The server owns the monthly cost limit. The Android client must never
        // strand the listener at the end of a five-item queue.
        return true;
    }

    public void recordSkipped(Episode episode) {
        if (episode == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (String tag : episode.getTags()) {
            String key = SKIPPED_PREFIX + normalize(tag);
            editor.putInt(key, clamp(preferences.getInt(key, 0) + 1, 0, 30));
        }
        rememberTitle(editor, episode.getTitle());
        editor.putLong(LAST_PLAYED_PREFIX + episode.getId(), System.currentTimeMillis());
        editor.putLong(TITLE_LAST_PLAYED_PREFIX + titleKey(episode.getTitle()), System.currentTimeMillis());
        editor.apply();
    }

    public String compactProfile() {
        Map<String, ?> values = preferences.getAll();
        ArrayList<String> sections = new ArrayList<>();
        String liked = rankedTags(values, TAG_PREFIX, true, 7);
        String disliked = rankedTags(values, TAG_PREFIX, false, 7);
        String completed = rankedCounts(values, COMPLETED_PREFIX, 7);
        String skipped = rankedCounts(values, SKIPPED_PREFIX, 5);
        if (!liked.isEmpty()) sections.add("Нравится: " + liked);
        if (!disliked.isEmpty()) sections.add("Не нравится: " + disliked);
        if (!completed.isEmpty()) sections.add("Дослушивает: " + completed);
        if (!skipped.isEmpty()) sections.add("Часто пропускает: " + skipped);
        String recent = preferences.getString(RECENT_TITLES, "");
        if (recent != null && !recent.trim().isEmpty()) {
            sections.add("Недавние выпуски, которые не надо повторять: "
                    + recent.replace('\u001f', ';'));
        }
        return join(sections, ". ");
    }

    public ArrayList<String> getRecentTitles() {
        ArrayList<String> titles = new ArrayList<>();
        String recent = preferences.getString(RECENT_TITLES, "");
        if (recent == null || recent.isEmpty()) return titles;
        for (String value : recent.split(String.valueOf('\u001f'))) {
            String clean = value.trim();
            if (!clean.isEmpty()) titles.add(clean);
        }
        return titles;
    }

    public String getVoicePreset() {
        return preferences.getString(VOICE_PRESET, VOICE_DUO_BEST);
    }

    public void setVoicePreset(String preset) {
        preferences.edit().putString(VOICE_PRESET, preset).apply();
    }

    public static String voicePresetLabel(String preset) {
        if (VOICE_DUO_FAST.equals(preset)) return "Мужчина + женщина · быстрее";
        if (VOICE_MALE.equals(preset)) return "Google · мужской голос";
        if (VOICE_FEMALE.equals(preset)) return "Google · женский голос";
        if (VOICE_SYSTEM.equals(preset)) return "Системный голос · офлайн";
        return "Мужчина + женщина · экономный подкаст";
    }

    public boolean isMusicMixEnabled() {
        return preferences.getBoolean(MUSIC_MIX_ENABLED, true);
    }

    public void setMusicMixEnabled(boolean enabled) {
        preferences.edit().putBoolean(MUSIC_MIX_ENABLED, enabled).apply();
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase().replaceAll("[^a-zа-яё0-9_-]+", "_");
    }

    private void rememberTitle(SharedPreferences.Editor editor, String title) {
        String clean = title == null ? "" : title.trim().replace('\u001f', ' ');
        if (clean.isEmpty()) return;
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        titles.add(clean);
        String previous = preferences.getString(RECENT_TITLES, "");
        if (previous != null && !previous.isEmpty()) {
            for (String value : previous.split(String.valueOf('\u001f'))) {
                if (!value.trim().isEmpty()) titles.add(value.trim());
                if (titles.size() >= 100) break;
            }
        }
        editor.putString(RECENT_TITLES, join(new ArrayList<>(titles), "\u001f"));
    }

    private boolean wasInLegacyRecentList(String title) {
        if (title == null || title.trim().isEmpty()) return false;
        String recent = preferences.getString(RECENT_TITLES, "");
        if (recent == null || recent.isEmpty()) return false;
        for (String value : recent.split(String.valueOf('\u001f'))) {
            if (title.trim().equalsIgnoreCase(value.trim())) return true;
        }
        return false;
    }

    private static String rankedTags(Map<String, ?> values, String prefix, boolean positive, int limit) {
        ArrayList<TagStat> stats = new ArrayList<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof Integer)) continue;
            int score = (Integer) entry.getValue();
            if ((positive && score <= 0) || (!positive && score >= 0)) continue;
            stats.add(new TagStat(readableTag(entry.getKey().substring(prefix.length())), score));
        }
        Collections.sort(stats, positive
                ? Comparator.comparingInt((TagStat value) -> value.score).reversed()
                : Comparator.comparingInt(value -> value.score));
        return formatStats(stats, limit, true);
    }

    private static String rankedCounts(Map<String, ?> values, String prefix, int limit) {
        ArrayList<TagStat> stats = new ArrayList<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof Integer)) continue;
            int count = (Integer) entry.getValue();
            if (count > 0) stats.add(new TagStat(
                    readableTag(entry.getKey().substring(prefix.length())), count));
        }
        Collections.sort(stats,
                Comparator.comparingInt((TagStat value) -> value.score).reversed());
        return formatStats(stats, limit, false);
    }

    private static String formatStats(List<TagStat> stats, int limit, boolean signed) {
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, stats.size()); i++) {
            TagStat stat = stats.get(i);
            values.add(stat.tag + " " + (signed && stat.score > 0 ? "+" : "") + stat.score);
        }
        return join(values, ", ");
    }

    private static String readableTag(String value) {
        return value.replace('_', ' ').trim();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static final class TagStat {
        final String tag;
        final int score;

        TagStat(String tag, int score) {
            this.tag = tag;
            this.score = score;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String titleKey(String title) {
        return Integer.toHexString(normalize(title).hashCode());
    }
}
