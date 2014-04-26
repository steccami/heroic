package com.spotify.heroic.backend;

import java.util.Map;

import com.spotify.heroic.model.TimeSerie;

public interface TimeSerieMatcher {
    public boolean matches(TimeSerie timeserie);

    public String indexKey();

    public Map<String, String> indexTags();
}