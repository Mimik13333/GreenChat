package pl.mimik.greenchat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class GreenChat extends JavaPlugin implements Listener {

    private final Map<UUID, MuteData> mutedPlayers = new HashMap<>();
    private final Map<UUID, Long> slowmode = new HashMap<>();

    private boolean chatEnabled = true;
    private int slowmodeSeconds = 0;

    private File muteFile;
    private FileConfiguration muteData;

    private static final String MENU_TITLE =
            ChatColor.DARK_GREEN + "GreenChat";

    @Override
    public void onEnable() {

        saveDefaultConfig();

        muteFile = new File(getDataFolder(), "mutes.yml");

        if (!muteFile.exists()) {
            try {
                muteFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Nie można utworzyć mutes.yml!");
            }
        }

        muteData = YamlConfiguration.loadConfiguration(muteFile);

        loadMutes();

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(
                getCommand("greenchat")
        ).setExecutor(new GreenChatCommand(this));

        Objects.requireNonNull(
                getCommand("greenchat")
        ).setTabCompleter(new GreenChatTabCompleter());

        getLogger().info("GreenChat został włączony!");
    }

    @Override
    public void onDisable() {
        saveMutes();
    }

    // =========================
    // WIADOMOŚCI
    // =========================

    public String message(String path) {
        String msg = getConfig().getString(
                "messages." + path,
                ""
        );

        return ChatColor.translateAlternateColorCodes(
                '&',
                msg
        );
    }

    public String message(
            String path,
            String... replacements
    ) {

        String msg = message(path);

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(
                    replacements[i],
                    replacements[i + 1]
            );
        }

        return msg;
    }

    // =========================
    // CHAT
    // =========================

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {

        Player player = event.getPlayer();

        MuteData mute = mutedPlayers.get(
                player.getUniqueId()
        );

        if (mute != null) {

            if (!mute.isExpired()) {

                event.setCancelled(true);

                player.sendMessage(
                        message(
                                "muted",
                                "{time}",
                                mute.getRemaining()
                        )
                );

                return;

            } else {

                mutedPlayers.remove(
                        player.getUniqueId()
                );

                saveMutes();
            }
        }

        if (!chatEnabled &&
                !player.hasPermission("greenchat.bypass")) {

            event.setCancelled(true);

            player.sendMessage(
                    message("chat-disabled")
            );

            return;
        }

        if (slowmodeSeconds > 0 &&
                !player.hasPermission("greenchat.bypass")) {

            long now = System.currentTimeMillis();

            Long lastMessage =
                    slowmode.get(player.getUniqueId());

            if (lastMessage != null) {

                long difference =
                        now - lastMessage;

                long required =
                        slowmodeSeconds * 1000L;

                if (difference < required) {

                    event.setCancelled(true);

                    long remaining =
                            (required - difference) / 1000;

                    player.sendMessage(
                            message(
                                    "slowmode",
                                    "{time}",
                                    String.valueOf(
                                            Math.max(
                                                    1,
                                                    remaining
                                            )
                                    )
                            )
                    );

                    return;
                }
            }

            slowmode.put(
                    player.getUniqueId(),
                    now
            );
        }

        String format =
                getConfig().getString(
                        "chat-format",
                        "&7{player} &8» &f{message}"
                );

        format = format
                .replace(
                        "{player}",
                        player.getName()
                )
                .replace(
                        "{message}",
                        event.getMessage()
                );

        event.setFormat(
                ChatColor.translateAlternateColorCodes(
                        '&',
                        format
                )
        );
    }

    // =========================
    // MENU
    // =========================

    public void openMenu(Player player) {

        Inventory inventory =
                Bukkit.createInventory(
                        null,
                        27,
                        MENU_TITLE
                );

        inventory.setItem(
                11,
                item(
                        chatEnabled
                                ? Material.LIME_DYE
                                : Material.RED_DYE,

                        chatEnabled
                                ? "&aChat: WŁĄCZONY"
                                : "&cChat: WYŁĄCZONY",

                        chatEnabled
                                ? "&7Kliknij, aby wyłączyć chat."
                                : "&7Kliknij, aby włączyć chat."
                )
        );

        inventory.setItem(
                13,
                item(
                        Material.PLAYER_HEAD,
                        "&aMutelist",
                        "&7Kliknij, aby zobaczyć",
                        "&7wyciszonych graczy."
                )
        );

        inventory.setItem(
                15,
                item(
                        Material.CLOCK,
                        "&eSlowmode",
                        "&7Aktualnie: &f" +
                                slowmodeSeconds +
                                " sekund",
                        "",
                        "&7Kliknij, aby zmienić."
                )
        );

        inventory.setItem(
                22,
                item(
                        Material.BARRIER,
                        "&cWyczyść chat",
                        "&7Kliknij, aby wyczyścić chat."
                )
        );

        player.openInventory(inventory);
    }

    @EventHandler
    public void onMenuClick(
            InventoryClickEvent event
    ) {

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        if (!event.getView()
                .getTitle()
                .equals(MENU_TITLE)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();

        if (slot == 11) {

            chatEnabled = !chatEnabled;

            player.sendMessage(
                    chatEnabled
                            ? message("chat-enabled")
                            : message("chat-disabled-admin")
            );

            openMenu(player);

            return;
        }

        if (slot == 13) {

            openMuteList(player);

            return;
        }

        if (slot == 15) {

            slowmodeSeconds += 5;

            if (slowmodeSeconds > 60) {
                slowmodeSeconds = 0;
            }

            player.sendMessage(
                    message(
                            "slowmode-changed",
                            "{time}",
                            String.valueOf(
                                    slowmodeSeconds
                            )
                    )
            );

            openMenu(player);

            return;
        }

        if (slot == 22) {

            for (Player online :
                    Bukkit.getOnlinePlayers()) {

                online.sendMessage(
                        ""
                );
            }

            player.sendMessage(
                    message("chat-cleared")
            );
        }
    }

    // =========================
    // MUTELIST
    // =========================

    public void openMuteList(Player player) {

        Inventory inventory =
                Bukkit.createInventory(
                        null,
                        54,
                        ChatColor.DARK_RED +
                                "Mutelist"
                );

        int slot = 0;

        for (Map.Entry<UUID, MuteData> entry :
                mutedPlayers.entrySet()) {

            if (slot >= 45) {
                break;
            }

            OfflinePlayer target =
                    Bukkit.getOfflinePlayer(
                            entry.getKey()
                    );

            ItemStack head =
                    new ItemStack(
                            Material.PLAYER_HEAD
                    );

            ItemMeta meta =
                    head.getItemMeta();

            if (meta != null) {

                meta.setDisplayName(
                        ChatColor.RED +
                                target.getName()
                );

                meta.setLore(
                        List.of(
                                ChatColor.GRAY +
                                        "Pozostało: " +
                                        entry.getValue()
                                                .getRemaining(),
                                "",
                                ChatColor.YELLOW +
                                        "Kliknij, aby odciszyć."
                        )
                );

                head.setItemMeta(meta);
            }

            inventory.setItem(
                    slot++,
                    head
            );
        }

        if (mutedPlayers.isEmpty()) {

            inventory.setItem(
                    22,
                    item(
                            Material.LIME_DYE,
                            "&aBrak wyciszonych graczy.",
                            "&7Nikt obecnie nie jest wyciszony."
                    )
            );
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onMuteListClick(
            InventoryClickEvent event
    ) {

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        if (!event.getView()
                .getTitle()
                .equals(
                        ChatColor.DARK_RED +
                                "Mutelist"
                )) {
            return;
        }

        event.setCancelled(true);

        ItemStack item =
                event.getCurrentItem();

        if (item == null ||
                item.getType() != Material.PLAYER_HEAD) {
            return;
        }

        ItemMeta meta =
                item.getItemMeta();

        if (meta == null ||
                meta.getDisplayName() == null) {
            return;
        }

        String name =
                ChatColor.stripColor(
                        meta.getDisplayName()
                );

        Player target =
                Bukkit.getPlayer(name);

        UUID uuid;

        if (target != null) {
            uuid = target.getUniqueId();
        } else {

            OfflinePlayer offline =
                    Bukkit.getOfflinePlayer(name);

            uuid = offline.getUniqueId();
        }

        if (mutedPlayers.containsKey(uuid)) {

            mutedPlayers.remove(uuid);

            saveMutes();

            if (target != null) {

                target.sendMessage(
                        message("unmuted-player")
                );
            }

            player.sendMessage(
                    message(
                            "unmute-success",
                            "{player}",
                            name
                    )
            );

            openMuteList(player);
        }
    }

    // =========================
    // MUTE
    // =========================

    public void mute(
            Player executor,
            Player target,
            long duration,
            String reason
    ) {

        long end;

        if (duration <= 0) {
            end = -1;
        } else {
            end =
                    System.currentTimeMillis()
                            + duration;
        }

        mutedPlayers.put(
                target.getUniqueId(),
                new MuteData(
                        end,
                        reason
                )
        );

        saveMutes();

        executor.sendMessage(
                message(
                        "mute-success",
                        "{player}",
                        target.getName(),
                        "{time}",
                        formatTime(duration)
                )
        );

        target.sendMessage(
                message(
                        "you-are-muted",
                        "{time}",
                        formatTime(duration),
                        "{reason}",
                        reason
                )
        );
    }

    public void unmute(
            Player executor,
            String name
    ) {

        Player target =
                Bukkit.getPlayerExact(name);

        if (target == null) {

            executor.sendMessage(
                    message("player-not-found")
            );

            return;
        }

        if (!mutedPlayers.containsKey(
                target.getUniqueId()
        )) {

            executor.sendMessage(
                    message(
                            "not-muted",
                            "{player}",
                            target.getName()
                    )
            );

            return;
        }

        mutedPlayers.remove(
                target.getUniqueId()
        );

        saveMutes();

        executor.sendMessage(
                message(
                        "unmute-success",
                        "{player}",
                        target.getName()
                )
        );

        target.sendMessage(
                message("unmuted-player")
        );
    }

    // =========================
    // CLEAR CHAT
    // =========================

    public void clearChat() {

        for (Player player :
                Bukkit.getOnlinePlayers()) {

            for (int i = 0; i < 100; i++) {
                player.sendMessage("");
            }

            player.sendMessage(
                    message("chat-cleared")
            );
        }
    }

    // =========================
    // HELP
    // =========================

    public void sendHelp(Player player) {

        String[] help =
                getConfig().getStringList(
                        "help"
                ).toArray(
                        new String[0]
                );

        for (String line : help) {

            player.sendMessage(
                    ChatColor.translateAlternateColorCodes(
                            '&',
                            line
                    )
            );
        }
    }

    // =========================
    // ITEM
    // =========================

    private ItemStack item(
            Material material,
            String name,
            String... lore
    ) {

        ItemStack item =
                new ItemStack(material);

        ItemMeta meta =
                item.getItemMeta();

        if (meta != null) {

            meta.setDisplayName(
                    ChatColor.translateAlternateColorCodes(
                            '&',
                            name
                    )
            );

            List<String> lines =
                    new ArrayList<>();

            for (String line : lore) {

                lines.add(
                        ChatColor.translateAlternateColorCodes(
                                '&',
                                line
                        )
                );
            }

            meta.setLore(lines);

            item.setItemMeta(meta);
        }

        return item;
    }

    // =========================
    // MUTE DATA
    // =========================

    private void loadMutes() {

        if (muteData == null) {
            return;
        }

        if (muteData.getConfigurationSection(
                "mutes"
        ) == null) {
            return;
        }

        for (String key :
                muteData.getConfigurationSection(
                        "mutes"
                ).getKeys(false)) {

            try {

                UUID uuid =
                        UUID.fromString(key);

                long end =
                        muteData.getLong(
                                "mutes." +
                                        key +
                                        ".end"
                        );

                String reason =
                        muteData.getString(
                                "mutes." +
                                        key +
                                        ".reason",
                                "Brak powodu"
                        );

                mutedPlayers.put(
                        uuid,
                        new MuteData(
                                end,
                                reason
                        )
                );

            } catch (Exception ignored) {
            }
        }
    }

    private void saveMutes() {

        if (muteData == null) {
            return;
        }

        muteData.set("mutes", null);

        for (Map.Entry<UUID, MuteData> entry :
                mutedPlayers.entrySet()) {

            String path =
                    "mutes." +
                            entry.getKey();

            muteData.set(
                    path + ".end",
                    entry.getValue().end
            );

            muteData.set(
                    path + ".reason",
                    entry.getValue().reason
            );
        }

        try {

            muteData.save(muteFile);

        } catch (IOException e) {

            getLogger().severe(
                    "Nie można zapisać mutes.yml!"
            );
        }
    }

    // =========================
    // CZAS
    // =========================

    public static long parseTime(
            String input
    ) {

        try {

            if (input.equalsIgnoreCase("0")) {
                return 0;
            }

            char unit =
                    input.charAt(
                            input.length() - 1
                    );

            long number =
                    Long.parseLong(
                            input.substring(
                                    0,
                                    input.length() - 1
                            )
                    );

            return switch (unit) {

                case 's', 'S' ->
                        number * 1000L;

                case 'm', 'M' ->
                        number * 60_000L;

                case 'h', 'H' ->
                        number * 3_600_000L;

                case 'd', 'D' ->
                        number * 86_400_000L;

                default -> -1;
            };

        } catch (Exception e) {

            return -1;
        }
    }

    private String formatTime(
            long milliseconds
    ) {

        if (milliseconds <= 0) {
            return "permanentnie";
        }

        long seconds =
                milliseconds / 1000;

        if (seconds < 60) {
            return seconds + " sekund";
        }

        long minutes =
                seconds / 60;

        if (minutes < 60) {
            return minutes + " minut";
        }

        long hours =
                minutes / 60;

        if (hours < 24) {
            return hours + " godzin";
        }

        long days =
                hours / 24;

        return days + " dni";
    }

    // =========================
    // KLASA MUTE
    // =========================

    public static class MuteData {

        private final long end;
        private final String reason;

        public MuteData(
                long end,
                String reason
        ) {

            this.end = end;
            this.reason = reason;
        }

        public boolean isExpired() {

            return end != -1 &&
                    System.currentTimeMillis()
                            >= end;
        }

        public String getRemaining() {

            if (end == -1) {
                return "permanentnie";
            }

            long remaining =
                    end -
                            System.currentTimeMillis();

            if (remaining <= 0) {
                return "0 sekund";
            }

            long seconds =
                    remaining / 1000;

            if (seconds < 60) {
                return seconds +
                        " sekund";
            }

            long minutes =
                    seconds / 60;

            if (minutes < 60) {
                return minutes +
                        " minut";
            }

            long hours =
                    minutes / 60;

            if (hours < 24) {
                return hours +
                        " godzin";
            }

            return (hours / 24) +
                    " dni";
        }
    }
}    
