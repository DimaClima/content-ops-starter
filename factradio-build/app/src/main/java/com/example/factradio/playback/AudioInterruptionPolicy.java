package com.example.factradio.playback;

/** Keeps navigation interruptions separate from an explicit user pause. */
public final class AudioInterruptionPolicy {
    private boolean resumeOnFocusGain;

    public void onUserPlay() {
        resumeOnFocusGain = false;
    }

    public void onUserPauseOrPermanentLoss() {
        resumeOnFocusGain = false;
    }

    public boolean onTransientLoss(boolean playbackIntended, boolean currentlyPlaying) {
        resumeOnFocusGain = playbackIntended && currentlyPlaying;
        return resumeOnFocusGain;
    }

    public boolean onFocusGain(boolean playbackIntended) {
        boolean shouldResume = resumeOnFocusGain && playbackIntended;
        resumeOnFocusGain = false;
        return shouldResume;
    }
}
