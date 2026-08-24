package com.example.factradio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.factradio.data.DemoEpisodes;
import com.example.factradio.data.PreferenceStore;
import com.example.factradio.model.Episode;
import com.example.factradio.playback.AudioInterruptionPolicy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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
