package com.schpeeniii.wynnranktab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public class WynnAPI {

    public record Guild(String name, String prefix, String rank) {
        public boolean hasGuild() {
            return prefix != null && rank != null;
        }

        public static Guild none() {
            return new Guild(null, null, null);
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private final String token;

    public WynnAPI(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public Guild fetch(String identifier) {
        try {
            String url = "https://api.wynncraft.com/v3/player/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);

            HttpRequest.Builder req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).header("User-Agent", "WynnRankTab-Plugin/1.0")
                    .header("Accept", "Application/json").GET();

            if (!token.isEmpty()) {
                req.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> res = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                return Guild.none();
            }

            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            if (!root.has("guild") || root.get("guild").isJsonNull()) {
                return Guild.none();
            }

            JsonObject g = root.getAsJsonObject("guild");
            String name = optString(g, "name");
            String prefix = optString(g, "prefix");
            String rank = optString(g, "rank");

            if (prefix == null || rank == null) {
                return Guild.none();
            }


            return new Guild(name, prefix, rank.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Guild.none();
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
