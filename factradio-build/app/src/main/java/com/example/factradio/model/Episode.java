package com.example.factradio.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Episode implements Serializable {
    private final String id;
    private final String category;
    private final String title;
    private final String summary;
    private final String script;
    private final String audioUrl;
    private final List<DialogueLine> dialogue;
    private final List<String> tags;
    private final List<Source> sources;

    public Episode(
            String id,
            String category,
            String title,
            String summary,
            String script,
            List<String> tags,
            List<Source> sources
    ) {
        this(id, category, title, summary, script, "", Collections.emptyList(), tags, sources);
    }

    public Episode(
            String id,
            String category,
            String title,
            String summary,
            String script,
            String audioUrl,
            List<DialogueLine> dialogue,
            List<String> tags,
            List<Source> sources
    ) {
        this.id = id;
        this.category = category;
        this.title = title;
        this.summary = summary;
        this.script = script;
        this.audioUrl = audioUrl == null ? "" : audioUrl;
        this.dialogue = Collections.unmodifiableList(new ArrayList<>(dialogue));
        this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
    }

    public String getId() { return id; }
    public String getCategory() { return category; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getScript() { return script; }
    public String getAudioUrl() { return audioUrl; }
    public List<DialogueLine> getDialogue() { return dialogue; }
    public List<String> getTags() { return tags; }
    public List<Source> getSources() { return sources; }
}
