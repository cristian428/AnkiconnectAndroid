package com.kamwithk.ankiconnectandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Scraper {
    private final Context context;
    private final String SERVER_HOST = "https://forvo.com";
    private final String AUDIO_HTTP_HOST = "https://audio00.forvo.com";
    private final String DEFAULT_FORVO_LANGUAGE = "ja";
    private HashMap<String, String> queryComponents;

    public Scraper(Context context, HashMap<String, String> queryComponents) {
        this.context = context;
        this.queryComponents = queryComponents;
    }

    public void setLanguageFromQuery() {
        if (queryComponents.containsKey("language")) {
            DEFAULT_FORVO_LANGUAGE = queryComponents.get("language");
        }
    }

    public ArrayList<HashMap<String, String>> scrape(String word, String reading) throws IOException {
        setLanguageFromQuery(); // Set language from query components
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String forvoLanguage = preferences.getString("forvo_language", DEFAULT_FORVO_LANGUAGE);

        ArrayList<HashMap<String, String>> audio_sources = scrapeWord(word, forvoLanguage);

        // Get similar words audio if exact word isn't found
        if (audio_sources.size() == 0) {
            audio_sources = scrapeWord(reading, forvoLanguage);
        }
        if (audio_sources.size() == 0) {
            audio_sources = scrapeSearch(word, forvoLanguage);
        }
        if (audio_sources.size() == 0) {
            audio_sources = scrapeSearch(reading, forvoLanguage);
        }

        return audio_sources;
    }

    private ArrayList<HashMap<String, String>> scrapeWord(String word, String language) throws IOException {
        Document document = Jsoup.connect(SERVER_HOST + "/word/" + strip(word) + "/").get();
        Elements elements = document.select("#language-container-" + language + ">article>ul>li:not(.li-ad)");

        ArrayList<HashMap<String, String>> audio_sources = new ArrayList<>();

        for (Element element : elements) {
            String url = extractURL(Objects.requireNonNull(element.selectFirst(".play")));

            HashMap<String, String> user_details = new HashMap<>();
            user_details.put("name", "Forvo (" + extractUsername(element.text()) + ")");
            user_details.put("url", url);
            audio_sources.add(user_details);
        }

        return audio_sources;
    }

    private ArrayList<HashMap<String, String>> scrapeSearch(String input, String language) throws IOException {
        Document document = Jsoup.connect(SERVER_HOST + "/search/" + strip(input) + "/" + language + "/").get();
        Elements elements = document.select("ul.word-play-list-icon-size-l>li>.play");

        ArrayList<HashMap<String, String>> audio_sources = new ArrayList<>();

        for (Element element : elements) {
            HashMap<String, String> user_details = new HashMap<>();
            user_details.put("name", "Forvo Search");
            user_details.put("url", extractURL(element));
            audio_sources.add(user_details);
        }

        return audio_sources;
    }

    private String strip(String input) {
        return input.replaceAll("^[ \t]+|[ \t]+$", "");
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private String extractURL(Element span) {
        String play = span.attr("onclick");

        Pattern pattern = Pattern.compile("([^',\\(\\)]+)");
        Matcher m = pattern.matcher(play);

        m.find();
        m.find();
        m.find();

        String file = new String(Base64.decode(m.group(), Base64.DEFAULT), StandardCharsets.UTF_8);
        return AUDIO_HTTP_HOST + "/mp3/" + file;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private String extractUsername(String text) {
        Pattern pattern = Pattern.compile("Pronunciation by([^(]+)\\(");
        Matcher matcher = pattern.matcher(strip(text));
        matcher.find();

        return strip(Objects.requireNonNull(matcher.group(1)));
    }
}
