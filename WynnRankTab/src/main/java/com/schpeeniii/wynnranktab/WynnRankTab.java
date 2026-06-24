package com.schpeeniii.wynnranktab;

import com.google.gson.Gson;

import java.net.http.HttpClient;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.key.Key;


public final class WynnRankTab extends JavaPlugin implements Listener {

    private WynnAPI api;
    private GuildColor guildColors;
    private final ConcurrentHashMap<UUID, Long> lastFetch = new ConcurrentHashMap<>();

    private String format;
    private String noGuildFormat;
    private boolean showGuildless;
    private boolean lookUpByUUID;
    private long refreshMillis;

    private static final int PAD = 3;

    private final ConcurrentHashMap<UUID, WynnAPI.Guild> guildCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        HttpClient httpClient = HttpClient.newHttpClient();
        Gson gson = new Gson();

        api = new WynnAPI(httpClient, gson, getConfig().getString("api-token", ""));
        guildColors = new GuildColor(httpClient, gson, getLogger());

        getServer().getScheduler().runTaskTimerAsynchronously(
                this, guildColors::refresh, 0L, 20L * 60 * 30);

        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Long last = lastFetch.get(p.getUniqueId());
                if (last == null || now - last >= refreshMillis) {
                    refresh(p);
                }
            }
        }, 100L, 20L * 30L);

        getLogger().info("WynnTab enabled. Lookup by "
                + (lookUpByUUID ? "UUID" : "USERNAME")
                + ", refresh every " + (refreshMillis / 1000) + "s.");
    }

    public void loadSettings() {
        format = getConfig().getString("format", "&f%player%");
        noGuildFormat = getConfig().getString("no-guild-format", "&7%player%");
        showGuildless = getConfig().getBoolean("show-guildless", true);
        lookUpByUUID = !"USERNAME".equalsIgnoreCase(getConfig().getString("lookup-by", "UUID"));
        long seconds = Math.max(60L, getConfig().getLong("refresh-interval", 600L));
        refreshMillis = seconds * 1000L;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> refresh(e.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        guildCache.remove(event.getPlayer().getUniqueId());
    }

    private void refresh(Player player) {
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        final String identifier = lookUpByUUID ? uuid.toString() : name;
        lastFetch.put(uuid, System.currentTimeMillis());

        Runnable work = () -> {
            WynnAPI.Guild guild = api.fetch(identifier);
            guildCache.put(uuid, guild);
            final String listName = render(name, guild);
            final Component prefixPill = buildPrefixPill(guild);
            final Component rankPill = buildRankPill(guild);

            Bukkit.getScheduler().runTask(this, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) {
                    Component finalName = (listName != null)
                            ? LegacyComponentSerializer.legacySection().deserialize(listName)
                            : Component.text(online.getName());
                    if (rankPill != null) {
                        finalName = Component.empty().append(rankPill).append(Component.space()).append(finalName);
                    }
                    if (prefixPill != null) {
                        finalName = Component.empty().append(prefixPill).append(Component.space()).append(finalName);
                    }
                    online.playerListName(finalName);
                }
            });
        };

        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, work);
        } else {
            work.run();
        }
    }

    private String render(String playerName, WynnAPI.Guild guild) {
        if (!guild.hasGuild()) {
            if (!showGuildless) return null;
            return color(noGuildFormat.replace("%player%", playerName));
        }

        String rankKey = guild.rank().toUpperCase(Locale.ROOT);
        String rankName = rankKey;
        String rankColor = "&f";

        ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");
        if (ranks != null) {
            ConfigurationSection r = ranks.getConfigurationSection(rankKey);
            if (r != null) {
                rankName = r.getString("name", rankKey);
                rankColor = r.getString("color", "&f");
            }
        }

        String hex = guildColors.colorFor(guild.prefix()).orElse(null);
        String nameField = (hex != null) ? hexColor(hex) + playerName : playerName;

        String out = format
                .replace("%player%", nameField);
        return color(out);
    }

    private String hexColor(String hex) {
        try {
            return net.md_5.bungee.api.ChatColor.of(hex).toString();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private Component buildPrefixPill(WynnAPI.Guild guild) {
        String prefix = "";
        TextColor color = TextColor.fromHexString("#AAAAAA");
        if (!guild.hasGuild()) {
            prefix = "NONE";
        } else {
            prefix = guild.prefix();
            String hex = guildColors.colorFor(prefix).orElse(null);
            if (hex == null) return null;
            color = TextColor.fromHexString(hex);
            if (color == null) return null;
        }

        return buildPill(prefix.toUpperCase(Locale.ROOT), color);
    }

    private Component buildRankPill(WynnAPI.Guild guild) {
        if(!guild.hasGuild()) return null;
        String rank = guild.rank();
        String hex = guildColors.colorFor(guild.prefix()).orElse(null);
        if(hex == null) return null;
        TextColor color = TextColor.fromHexString(hex);
        if(color == null) return null;

        return buildPill(rank, color);
    }

    private Component buildPill(String text, TextColor color) {
        int T   = textWidth(text);
        int ink = T - 1;
        int W   = ink + 2 * PAD;
        int leftPad  = PAD;
        int rightPad = PAD - 1;
        Key tab = Key.key("wynntab", "tab");

        return Component.empty()
                .append(pillBg(W, color))
                .append(Component.text(space(-W)).font(tab))
                .append(Component.text(space(leftPad)).font(tab))
                .append(Component.text(text).font(Key.key("wynntab", "small")).color(NamedTextColor.WHITE))
                .append(Component.text(space(rightPad)).font(tab));
    }

    private Component slice(char g, TextColor color) {
        Key tab = Key.key("wynntab", "tab");
        return Component.empty()
                .append(Component.text(g).font(tab).color(color))
                .append(Component.text(space(-1)).font(tab));
    }

    private Component pillBg(int W, TextColor color) {
        int inner = W - 6;
        Component bg = slice('\uE030', color); //left shi
        int[] sizes  = {32, 16, 8, 4, 2, 1};
        char[] glyphs = {'\uE037','\uE036','\uE035','\uE034','\uE033','\uE032'};
        for (int i = 0; i < sizes.length; i++) {
            while (inner >= sizes[i]) {
                bg = bg.append(slice(glyphs[i], color));
                inner -= sizes[i];
            }
        }
        return bg.append(slice('\uE031', color)); //right shi
    }

    private int textWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += (c == 'I' || c == '1') ? 4 : 6;
        return w;
    }

    private static final char[] NEG = {'\uE100', '\uE101', '\uE102', '\uE103', '\uE104', '\uE105'};
    private static final char[] POS = {'\uE110', '\uE111', '\uE112', '\uE113', '\uE114', '\uE115'};

    private String space(int px) {
        if (px == 0) return "";
        char[] set = px < 0 ? NEG : POS;
        int n = Math.abs(px);
        StringBuilder sb = new StringBuilder();
        for (int i = set.length - 1; i >= 0; i--) {
            int val = 1 << i;
            while (n >= val) {
                sb.append(set[i]);
                n -= val;
            }
        }
        return sb.toString();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        WynnAPI.Guild g = guildCache.get(uuid);
        final WynnAPI.Guild guild = (g != null) ? g : WynnAPI.Guild.none();

        final Component prefix = buildPrefixPill(guild);
        final Component rank   = buildRankPill(guild);

        event.renderer((source, displayName, message, viewer) -> {
            Component line = Component.empty();
            if (prefix != null) line = line.append(prefix).append(Component.space());
            if (rank   != null) line = line.append(rank).append(Component.space());
            return line.append(displayName).append(Component.text(": ")).append(message);
        });
    }
}
