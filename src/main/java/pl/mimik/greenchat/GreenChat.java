package pl.mimik.greenchat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class GreenChat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String MENU_TITLE = "§2GreenChat §8• §7Menu";
    private static final String MUTELIST_TITLE = "§2GreenChat §8• §7Mutelist";
    private static final String MUTE_PLAYER_PREFIX = "§2GreenChat §8• §7Mute: ";

    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, String> muteReasons = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        loadMuteData();
        Objects.requireNonNull(getCommand("greenchat")).setExecutor(this);
        Objects.requireNonNull(getCommand("greenchat")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("GreenChat enabled.");
    }

    @Override
    public void onDisable() {
        saveMuteData();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String msg(String path) {
        return color(getConfig().getString("messages.prefix", "&2GreenChat &8» &f") + getConfig().getString(path, ""));
    }

    private void send(Player p, String path) {
        p.sendMessage(msg(path));
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("greenchat.admin")) return true;
        sender.sendMessage(msg("messages.no-permission"));
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("pomoc") || args[0].equalsIgnoreCase("help")) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "menu" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) { sender.sendMessage("Ta komenda wymaga gracza."); return true; }
                openMenu(p);
            }
            case "mutelist" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) { sender.sendMessage("Ta komenda wymaga gracza."); return true; }
                openMuteList(p, 0);
            }
            case "mute" -> handleMute(sender, args);
            case "unmute" -> handleUnmute(sender, args);
            case "clearchat" -> {
                if (!admin(sender)) return true;
                clearChat();
                Bukkit.broadcastMessage(msg("messages.cleared"));
            }
            case "slowmode" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) { sender.sendMessage(msg("messages.usage-slowmode")); return true; }
                try {
                    int seconds = Integer.parseInt(args[1]);
                    if (seconds < 0 || seconds > 3600) throw new NumberFormatException();
                    getConfig().set("chat.slowmode-seconds", seconds);
                    saveConfig();
                    sender.sendMessage(msg("messages.slowmode").replace("%seconds%", String.valueOf(seconds)));
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cPodaj liczbę od 0 do 3600.");
                }
            }
            case "broadcast" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) { sender.sendMessage(msg("messages.broadcast-usage")); return true; }
                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Bukkit.broadcastMessage(color(getConfig().getString("messages.broadcast-format")).replace("%message%", text));
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                reloadConfig();
                saveMuteData();
                sender.sendMessage(msg("messages.reload"));
            }
            default -> showHelp(sender);
        }
        return true;
    }

    private void showHelp(CommandSender sender) {
        for (String line : getConfig().getStringList("help")) sender.sendMessage(color(line));
    }

    private void handleMute(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 3) { sender.sendMessage(msg("messages.usage-mute")); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage(msg("messages.unknown-player")); return; }

        long duration;
        try { duration = parseDuration(args[2]); }
        catch (IllegalArgumentException e) { sender.sendMessage("§cNieprawidłowy czas. Przykłady: 30s, 5m, 2h, 1d, 0 = permanentnie."); return; }

        long until = duration == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + duration;
        mutedUntil.put(target.getUniqueId(), until);
        String reason = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "Brak powodu";
        muteReasons.put(target.getUniqueId(), reason);
        saveMuteData();

        sender.sendMessage(msg("messages.mute-success").replace("%player%", target.getName()).replace("%time%", duration == 0 ? "na zawsze" : args[2]));
        target.sendMessage("§cZostałeś wyciszony. §7Powód: §f" + reason);
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length < 2) { sender.sendMessage(msg("messages.usage-unmute")); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        if (!isMuted(uuid)) { sender.sendMessage(msg("messages.not-muted")); return; }
        mutedUntil.remove(uuid);
        muteReasons.remove(uuid);
        saveMuteData();
        sender.sendMessage(msg("messages.unmute-success").replace("%player%", args[1]));
        if (target != null) target.sendMessage("§aZostałeś odciszony.");
    }

    private long parseDuration(String input) {
        if (input.equalsIgnoreCase("0") || input.equalsIgnoreCase("perm")) return 0;
        if (input.length() < 2) throw new IllegalArgumentException();
        char unit = Character.toLowerCase(input.charAt(input.length() - 1));
        long number = Long.parseLong(input.substring(0, input.length() - 1));
        if (number < 1) throw new IllegalArgumentException();
        return switch (unit) {
            case 's' -> number * 1000L;
            case 'm' -> number * 60_000L;
            case 'h' -> number * 3_600_000L;
            case 'd' -> number * 86_400_000L;
            default -> throw new IllegalArgumentException();
        };
    }

    private boolean isMuted(UUID uuid) {
        Long until = mutedUntil.get(uuid);
        if (until == null) return false;
        if (until != Long.MAX_VALUE && until <= System.currentTimeMillis()) {
            mutedUntil.remove(uuid);
            muteReasons.remove(uuid);
            saveMuteData();
            return false;
        }
        return true;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("greenchat.bypass")) return;

        if (!getConfig().getBoolean("chat.enabled", true)) {
            event.setCancelled(true);
            p.sendMessage(msg("messages.chat-disabled"));
            return;
        }

        if (isMuted(p.getUniqueId())) {
            event.setCancelled(true);
            long until = mutedUntil.get(p.getUniqueId());
            String time = until == Long.MAX_VALUE ? "na zawsze" : formatRemaining(until - System.currentTimeMillis());
            p.sendMessage(msg("messages.muted").replace("%time%", time));
            return;
        }

        int slow = getConfig().getInt("chat.slowmode-seconds", 0);
        if (slow > 0) {
            long now = System.currentTimeMillis();
            long previous = lastMessage.getOrDefault(p.getUniqueId(), 0L);
            long remaining = slow * 1000L - (now - previous);
            if (remaining > 0) {
                event.setCancelled(true);
                p.sendMessage("§cOdczekaj jeszcze §f" + formatRemaining(remaining) + "§c.");
                return;
            }
            lastMessage.put(p.getUniqueId(), now);
        }

        String format = color(getConfig().getString("chat.format", "&8[&a%player%&8] &f%message%"));
        event.setFormat(format.replace("%player%", p.getName()).replace("%message%", "%2$s"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);
        boolean enabled = getConfig().getBoolean("chat.enabled", true);
        int slow = getConfig().getInt("chat.slowmode-seconds", 0);

        inv.setItem(10, item(enabled ? Material.LIME_DYE : Material.RED_DYE,
                enabled ? "§a§lCHAT: ON" : "§c§lCHAT: OFF",
                "§7Kliknij, aby " + (enabled ? "wyłączyć" : "włączyć") + " chat."));
        inv.setItem(12, item(Material.PLAYER_HEAD, "§e§lMUTELIST", "§7Lista wyciszonych graczy."));
        inv.setItem(14, item(Material.PAPER, "§b§lSLOWMODE", "§7Aktualnie: §f" + slow + "s", "§7Kliknij, aby wyłączyć."));
        inv.setItem(16, item(Material.BARRIER, "§c§lWYCZYŚĆ CHAT", "§7Kliknij, aby wyczyścić chat."));
        inv.setItem(22, item(Material.REDSTONE, "§6§lRELOAD", "§7Przeładuj konfigurację."));
        p.openInventory(inv);
    }

    private void openMuteList(Player p, int page) {
        List<UUID> active = new ArrayList<>();
        for (UUID uuid : new ArrayList<>(mutedUntil.keySet())) if (isMuted(uuid)) active.add(uuid);
        int maxPage = Math.max(0, (active.size() - 1) / 21);
        page = Math.max(0, Math.min(page, maxPage));
        Inventory inv = Bukkit.createInventory(null, 27, MUTELIST_TITLE);
        int start = page * 21;
        for (int i = 0; i < 21 && start + i < active.size(); i++) {
            UUID uuid = active.get(start + i);
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            long until = mutedUntil.get(uuid);
            String time = until == Long.MAX_VALUE ? "na zawsze" : formatRemaining(until - System.currentTimeMillis());
            inv.setItem(i, item(Material.PLAYER_HEAD, "§c" + (op.getName() == null ? uuid.toString().substring(0, 8) : op.getName()),
                    "§7Pozostało: §f" + time, "§7Powód: §f" + muteReasons.getOrDefault(uuid, "Brak"), "", "§aKliknij, aby odmutować."));
        }
        inv.setItem(21, item(Material.ARROW, "§ePoprzednia strona", "§7Strona: " + (page + 1) + "/" + (maxPage + 1)));
        inv.setItem(22, item(Material.BARRIER, "§cZamknij"));
        inv.setItem(23, item(Material.ARROW, "§eNastępna strona", "§7Strona: " + (page + 1) + "/" + (maxPage + 1)));
        p.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        String title = event.getView().getTitle();
        if (!title.equals(MENU_TITLE) && !title.equals(MUTELIST_TITLE) && !title.startsWith(MUTE_PLAYER_PREFIX)) return;
        event.setCancelled(true);
        if (title.equals(MENU_TITLE)) {
            int slot = event.getRawSlot();
            if (slot == 10) {
                boolean newState = !getConfig().getBoolean("chat.enabled", true);
                getConfig().set("chat.enabled", newState); saveConfig();
                send(p, newState ? "messages.chat-status-on" : "messages.chat-status-off"); openMenu(p);
            } else if (slot == 12) openMuteList(p, 0);
            else if (slot == 14) { getConfig().set("chat.slowmode-seconds", 0); saveConfig(); openMenu(p); }
            else if (slot == 16) { clearChat(); Bukkit.broadcastMessage(msg("messages.cleared")); openMenu(p); }
            else if (slot == 22) { reloadConfig(); send(p, "messages.reload"); openMenu(p); }
        } else if (title.equals(MUTELIST_TITLE)) {
            int slot = event.getRawSlot();
            if (slot == 22) p.closeInventory();
            else if (slot == 21 || slot == 23) {
                // Page switching is intentionally simple: rebuild from current visible page.
                openMuteList(p, slot == 23 ? 1 : 0);
            } else if (slot >= 0 && slot < 21) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && clicked.hasItemMeta() && clicked.getItemMeta().getDisplayName() != null) {
                    String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
                    OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                    if (isMuted(op.getUniqueId())) {
                        mutedUntil.remove(op.getUniqueId()); muteReasons.remove(op.getUniqueId()); saveMuteData();
                        p.sendMessage("§aOdciszono §f" + name + "§a.");
                        openMuteList(p, 0);
                    }
                }
            }
        }
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(Arrays.asList(lore)); item.setItemMeta(meta); }
        return item;
    }

    private void clearChat() {
        for (Player p : Bukkit.getOnlinePlayers()) for (int i = 0; i < 120; i++) p.sendMessage("");
    }

    private String formatRemaining(long millis) {
        long total = Math.max(0, millis / 1000);
        long d = total / 86400; total %= 86400;
        long h = total / 3600; total %= 3600;
        long m = total / 60; long s = total % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private void loadMuteData() {
        reloadConfig();
        ConfigurationSection section = getConfig().getConfigurationSection("mutes");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long until = section.getLong(key + ".until");
                if (until == Long.MAX_VALUE || until > System.currentTimeMillis()) {
                    mutedUntil.put(uuid, until);
                    muteReasons.put(uuid, section.getString(key + ".reason", "Brak"));
                }
            } catch (Exception ignored) {}
        }
    }

    private void saveMuteData() {
        getConfig().set("mutes", null);
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(mutedUntil.keySet())) {
            Long until = mutedUntil.get(uuid);
            if (until == null) continue;
            if (until != Long.MAX_VALUE && until <= now) {
                mutedUntil.remove(uuid);
                muteReasons.remove(uuid);
                continue;
            }
            getConfig().set("mutes." + uuid + ".until", until);
            getConfig().set("mutes." + uuid + ".reason", muteReasons.getOrDefault(uuid, "Brak"));
        }
        saveConfig();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("pomoc", "menu", "mutelist", "mute", "unmute", "clearchat", "slowmode", "broadcast", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && (args[0].equalsIgnoreCase("mute") || args[0].equalsIgnoreCase("unmute"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        return Collections.emptyList();
    }
}
