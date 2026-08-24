package com.example.factradio.model;

import java.io.Serializable;

public final class Source implements Serializable {
    private final String title;
    private final String url;

    public Source(String title, String url) {
        this.title = title;
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }
}
