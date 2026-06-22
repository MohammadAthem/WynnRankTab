package com.schpeeniii.wynnranktab;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;

import javax.swing.text.html.Option;
import java.lang.reflect.Type;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public class GuildColor {

    private static final String URL = "https://athena.wynntils.com/cache/get/guildList";

    private record GuildEntry(String id, String prefix, String color) {}

    private final HttpClient http;
    private final Gson gson;
    private final Logger logger;

    private volatile Map<String, String> colorsByPrefix = Map.of();
    private volatile Map<String, String> displayByPrefix = Map.of();

    public GuildColor(HttpClient http, Gson gson, Logger logger){
        this.http = http;
        this.gson = gson;
        this.logger = logger;
    }

    public Optional<String> colorFor(String prefix) {
        if (prefix == null) return Optional.empty();
        return Optional.ofNullable(colorsByPrefix.get(prefix.toUpperCase(Locale.ROOT)));
    }

    public Optional<String> displayPrefix(String prefix) { //useless code, too lazy to take out
        if (prefix == null) return Optional.empty();
        return Optional.ofNullable(displayByPrefix.get(prefix.toUpperCase(Locale.ROOT)));
    }


    public void refresh() {
        try{
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "WynnRankTab-Plugin")
                    .GET().build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if(res.statusCode() != 200){
                logger.warning("Athena guildList HTTP " + res.statusCode() + " — keeping old colors");
                return;
            }

            Type type = new TypeToken<List<GuildEntry>>() {}.getType();
            List<GuildEntry> raw = gson.fromJson(res.body(), type);

            Map<String, String> nextColors = new HashMap<>();
            Map<String, String> nextDisplay = new HashMap<>();
            for (GuildEntry g : raw) {
                if (g == null || g.prefix() == null || g.color() == null || g.color().isBlank()) continue;
                String key = g.prefix().toUpperCase(Locale.ROOT);
                nextColors.put(key, g.color());
                nextDisplay.put(key, g.prefix());
            }

            colorsByPrefix  = Map.copyOf(nextColors);
            displayByPrefix = Map.copyOf(nextDisplay);
            logger.info("Loaded " + colorsByPrefix.size());

        } catch (Exception e){
            logger.warning("Guild color refresh failed " + e.getMessage() + ", Keeping old colors");
        }
    }


}
