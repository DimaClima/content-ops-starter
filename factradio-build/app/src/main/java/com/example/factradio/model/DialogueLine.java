package com.example.factradio.model;

import java.io.Serializable;

public final class DialogueLine implements Serializable {
    public static final String MALE = "male";
    public static final String FEMALE = "female";

    private final String speaker;
    private final String text;

    public DialogueLine(String speaker, String text) {
        this.speaker = FEMALE.equals(speaker) ? FEMALE : MALE;
        this.text = text == null ? "" : text.trim();
    }

    public String getSpeaker() { return speaker; }
    public String getText() { return text; }
}
