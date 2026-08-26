package com.example.factradio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.media.browse.MediaBrowser;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.factradio.data.DemoEpisodes;
import com.example.factradio.data.PreferenceStore;
import com.example.factradio.model.Episode;
import com.example.factradio.playback.AudioInterruptionPolicy;
import com.example.factradio.playback.RadioPlaybackService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class FactRadioBehaviorTest {
    private Context context;

    @Before
    public void resetPreferences() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("fact_radio_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void physicalOrientationCoversPortraitAndLandscape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                MainActivity.requestedOrientationForDegrees(0));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                MainActivity.requestedOrientationForDegrees(90));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                MainActivity.requestedOrientationForDegrees(180));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                MainActivity.requestedOrientationForDegrees(270));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                MainActivity.requestedOrientationForGravity(0f, 9.8f));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                MainActivity.requestedOrientationForGravity(9.8f, 0f));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                MainActivity.requestedOrientationForGravity(-9.8f, 0f));
    }

    @Test
    public void startedEpisodeIsNotOfferedAgainOnRestart() {
        PreferenceStore store = new PreferenceStore(context);
        Episode first = DemoEpisodes.all().get(0);
        assertTrue(store.canPlayNow(first));
        store.recordStarted(first);
        assertFalse(store.canPlayNow(first));
        assertFalse(DemoEpisodes.personalized(context).stream()
                .anyMatch(episode -> first.getId().equals(episode.getId())));
    }

    @Test
    public void dislikeExcludesEpisode() {
        PreferenceStore store = new PreferenceStore(context);
        Episode first = DemoEpisodes.all().get(0);
        store.rateEpisode(first, -1);
        assertFalse(store.canPlayNow(first));
    }

    @Test
    public void sameTitleWithNewServerIdIsStillRecognizedAsHeard() {
        PreferenceStore store = new PreferenceStore(context);
        Episode first = new Episode("generated-a", "Наука", "Одинаковый заголовок",
                "Описание", "Текст", Collections.singletonList("наука"),
                Collections.emptyList());
        Episode regenerated = new Episode("generated-b", "Наука", "Одинаковый заголовок",
                "Другое описание", "Другой текст", Collections.singletonList("наука"),
                Collections.emptyList());
        store.recordStarted(first);
        assertFalse(store.canPlayNow(regenerated));
        store.rateEpisode(first, -1);
        assertEquals(-1, store.getEpisodeRating(regenerated));
    }

    @Test
    public void automaticGenerationIsNotStoppedAfterSixStories() {
        PreferenceStore store = new PreferenceStore(context);
        for (int i = 0; i < 20; i++) {
            assertTrue(store.reserveAutomaticRecommendation(true));
        }
    }

    @Test
    public void androidAutoCatalogAlwaysContainsNewStory() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<List<MediaBrowser.MediaItem>> children = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        final MediaBrowser[] browser = new MediaBrowser[1];
        browser[0] = new MediaBrowser(context,
                new ComponentName(context, RadioPlaybackService.class),
                new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        browser[0].subscribe(browser[0].getRoot(),
                                new MediaBrowser.SubscriptionCallback() {
                                    @Override public void onChildrenLoaded(String parentId,
                                                                           List<MediaBrowser.MediaItem> items) {
                                        children.set(items);
                                        loaded.countDown();
                                    }

                                    @Override public void onError(String parentId) {
                                        failure.set(new AssertionError("MediaBrowser catalog error"));
                                        loaded.countDown();
                                    }
                                });
                    }

                    @Override public void onConnectionFailed() {
                        failure.set(new AssertionError("MediaBrowser connection failed"));
                        loaded.countDown();
                    }
                }, null);
        browser[0].connect();
        assertTrue("Android Auto catalog timed out", loaded.await(8, TimeUnit.SECONDS));
        browser[0].disconnect();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(children.get() != null && !children.get().isEmpty());
        assertEquals("factradio:new", children.get().get(0).getMediaId());
        assertTrue(children.get().get(0).isPlayable());
    }

    @Test
    public void navigationPauseResumesButUserPauseDoesNot() {
        AudioInterruptionPolicy policy = new AudioInterruptionPolicy();
        policy.onUserPlay();
        assertTrue(policy.onTransientLoss(true, true));
        assertTrue(policy.onFocusGain(true));

        policy.onUserPlay();
        assertTrue(policy.onTransientLoss(true, true));
        policy.onUserPauseOrPermanentLoss();
        assertFalse(policy.onFocusGain(false));
    }
}
