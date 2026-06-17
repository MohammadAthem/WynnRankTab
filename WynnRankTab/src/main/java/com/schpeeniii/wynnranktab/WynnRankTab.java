package com.schpeeniii.wynnranktab;

import com.google.gson.Gson;
import java.net.http.HttpClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class WynnRankTab extends JavaPlugin implements Listener {

    private WynnAPI api;
    private GuildColor guildColors;
    private final ConcurrentHashMap<UUID, Long> lastFetch = new ConcurrentHashMap<>();

    private String format;
    private String noGuildFormat;
    private boolean showGuildless;
    private boolean lookUpByUUID;
    private long refreshMillis;

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

    public void loadSettings(){
        format = getConfig().getString("format", "&8[&b%prefix%&8] %rank% &f%player%");
        noGuildFormat = getConfig().getString("no-guild-format", "&7%player%");
        showGuildless = getConfig().getBoolean("show-guildless", true);
        lookUpByUUID   = !"USERNAME".equalsIgnoreCase(getConfig().getString("lookup-by", "UUID"));
        long seconds   = Math.max(60L, getConfig().getLong("refresh-interval", 600L));
        refreshMillis = seconds * 1000L;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> refresh(e.getPlayer()), 20L);
    }

    private void refresh(Player player) {
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        final String identifier = lookUpByUUID ? uuid.toString() : name;
        lastFetch.put(uuid, System.currentTimeMillis());

        Runnable work = () -> {
            WynnAPI.Guild guild = api.fetch(identifier);
            final String listName = render(name, guild);
            Bukkit.getScheduler().runTask(this, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) {
                    online.setPlayerListName(listName != null ? listName : online.getName());
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
        String prefixField = (hex != null) ? hexColor(hex) + guild.prefix().toUpperCase(Locale.ROOT) : guild.prefix().toUpperCase(Locale.ROOT);
        String rankField = (hex != null) ? hexColor(hex) + guild.rank() : guild.rank();
        String nameField = (hex != null) ? hexColor(hex) + playerName : playerName;

        String out = format
                .replace("%prefix%", prefixField)
                .replace("%rank%", rankField)
                .replace("%player%", nameField);
        return color(out);
    }

    private String hexColor(String hex){
        try{
            return net.md_5.bungee.api.ChatColor.of(hex).toString();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
