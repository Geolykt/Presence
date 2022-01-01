package de.geolykt.presence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.md_5.bungee.api.ChatColor;

import de.geolykt.presence.common.Configuration;
import de.geolykt.presence.common.DataSource;
import de.geolykt.presence.common.PresenceData;

public class PresenceBukkit extends JavaPlugin {

    // TODO dynmap integration

    private static final HashMap<UUID, UUID> PLAYER_LOCATIONS = new HashMap<>(); // For intelligent claim passing
    private static final HashMap<UUID, Score> SCOREBOARD_CLAIM_OWNER = new HashMap<>(); // For intelligent caching
    private static final HashMap<UUID, Score> SCOREBOARD_CLAIM_SELF = new HashMap<>();
    private static final HashMap<UUID, Score> SCOREBOARD_CLAIM_SUCCESSOR = new HashMap<>();
    private static final HashMap<UUID, Scoreboard> SCOREBOARD_SUBSCRIBERS = new HashMap<>();

    private static final Collection<UUID> TEMPORARY_FLIGHT = new HashSet<>();
    private static final Collection<UUID> SESSION_FLIGHT = new HashSet<>();
    private static final HashMap<UUID, Long> GRACEFUL_LAND = new HashMap<>();

    private static final void sendActionbarMessage(@NotNull Player p, @NotNull String message, TextColor color) {
        p.sendActionBar(Component.text(message, color));
    }

    void initSb(Player player, Scoreboard scoreboard) {
        scoreboard.clearSlot(DisplaySlot.SIDEBAR);
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkY = loc.getBlockZ() >> 4;
        UUID world = player.getWorld().getUID();
        PresenceData presenceData = DataSource.getData();
        UUID playerUID = player.getUniqueId();
        Map.Entry<UUID, Integer> leader = presenceData.getOwner(world, chunkX, chunkY);
        Map.Entry<UUID, Integer> successor = presenceData.getSuccessor(world, chunkX, chunkY);
        Objective objective = scoreboard.registerNewObjective("presence_claims", "dummy", Component.text("Presence claims: ", NamedTextColor.YELLOW, TextDecoration.BOLD), RenderType.INTEGER);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setRenderType(RenderType.INTEGER);
        Score claimownerPresence = objective.getScore("Owner presence");
        Score ownPresence = objective.getScore("Your presence");
        Score successorPresence = objective.getScore("Successor presence");
        SCOREBOARD_CLAIM_OWNER.put(playerUID, claimownerPresence);
        SCOREBOARD_CLAIM_SELF.put(playerUID, ownPresence);
        SCOREBOARD_CLAIM_SUCCESSOR.put(playerUID, successorPresence);
        if (leader == null) {
            claimownerPresence.setScore(0);
            ownPresence.setScore(0);
            successorPresence.setScore(0);
            return;
        }
        claimownerPresence.setScore(leader.getValue());
        if (leader.getKey().equals(playerUID)) {
            ownPresence.setScore(leader.getValue());
        } else {
            ownPresence.setScore(presenceData.getPresence(playerUID, world, chunkX, chunkY));
        }
        if (successor == null) {
            successorPresence.setScore(0);
        } else {
            successorPresence.setScore(successor.getValue());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
        case "claims":
            if (args.length == 0) {
                args = new @NotNull String[] {"help"}; // Because I could not be bothered to do this otherwise
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help":
                sender.sendMessage(ChatColor.YELLOW + "Options:");
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " help" + ChatColor.RED + " : " + ChatColor.WHITE + "prints this text.");
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " togglesb" + ChatColor.RED + " : " + ChatColor.WHITE + "toggles the scoreboard.");
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " map" + ChatColor.RED + " : " + ChatColor.WHITE + "prints a map of the surroundings.");
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " trust <player>" + ChatColor.RED + " : " + ChatColor.WHITE + "allows a player to modify your property.");
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " untrust <player>" + ChatColor.RED + " : " + ChatColor.WHITE + "reverses the trust command.");
                return true;
            case "togglesb":
                if (sender instanceof Player) {
                    Player plyr = ((Player)sender);
                    UUID id = plyr.getUniqueId();
                    ScoreboardManager mgr = Bukkit.getScoreboardManager();
                    if (SCOREBOARD_SUBSCRIBERS.containsKey(id)) {
                        SCOREBOARD_SUBSCRIBERS.remove(id);
                        sender.sendMessage(ChatColor.YELLOW + " Reset the scoreboard.");
                        plyr.setScoreboard(mgr.getMainScoreboard());
                    } else {
                        Scoreboard sb = mgr.getNewScoreboard();
                        SCOREBOARD_SUBSCRIBERS.put(id, sb);
                        initSb(plyr, sb);
                        plyr.setScoreboard(sb);
                        sender.sendMessage(ChatColor.DARK_GREEN + " Enabled the scoreboard.");
                    }
                }
                return true;
            case "map":
                printMap(sender);
                return true;
            case "trust": {
                if (args.length == 1) {
                    sender.sendMessage(Component.text("You have to specify the target player!", NamedTextColor.RED));
                    return true;
                }
                @SuppressWarnings("deprecation")
                OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
                if (!player.hasPlayedBefore()) {
                    sender.sendMessage(Component.text("The selected player did not play on this server (yet)!", NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("You need to be a player for that!", NamedTextColor.RED));
                    return true;
                }
                DataSource.getData().addTrust(((Player)sender).getUniqueId(), player.getUniqueId());
                sender.sendMessage(Component.text("You are now trusting " + player.getName() + ".", NamedTextColor.GREEN));
                return true;
            }
            case "untrust": {
                if (args.length == 1) {
                    sender.sendMessage(Component.text("You have to specify the target player!", NamedTextColor.RED));
                    return true;
                }
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (player == null || !player.hasPlayedBefore()) {
                    sender.sendMessage(Component.text("The selected player did not play on this server (or does not exist)!", NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("You need to be a player to be able to do that!", NamedTextColor.RED));
                    return true;
                }
                if (!DataSource.getData().isTrusted(((Player)sender).getUniqueId(), player.getUniqueId())) {
                    sender.sendMessage(Component.text("You are not yet trusting that player!", NamedTextColor.RED));
                    return true;
                }
                DataSource.getData().removeTrust(((Player)sender).getUniqueId(), player.getUniqueId());
                sender.sendMessage(Component.text("You are no longer trusting " + player.getName() + ".", NamedTextColor.GREEN));
                return true;
            }
            default:
                sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
                return true;
            }
            // Break irrelevant as all other branches return
        case "claimfly":
            if (!DataSource.getConfiguration().allowsFlight()) {
                sender.sendMessage(Component.text("Flight in claims is not enabled.", NamedTextColor.RED));
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("You need to be a player for this!", NamedTextColor.RED));
                return true;
            }
            Player player = (Player) sender;
            Location loc = player.getLocation();
            int chunkX = loc.getBlockX() >> 4;
            int chunkY = loc.getBlockZ() >> 4;
            UUID world = player.getWorld().getUID();
            if (!DataSource.getData().isOwnerOrTrusted(player.getUniqueId(), world, chunkX, chunkY)) {
                sender.sendMessage(Component.text("You are not in your claim!", NamedTextColor.RED));
                return true;
            }
            if (TEMPORARY_FLIGHT.remove(player.getUniqueId())) {
                removeFlight(player);
                sender.sendMessage(Component.text("You are no longer flying!", NamedTextColor.GREEN));
                return true;
            }
            Component flyingConfirm = Component.text("You are not able to fly in your claim!", NamedTextColor.GREEN);
            if (args.length == 2 && args[1].equalsIgnoreCase("temporary")) {
                SESSION_FLIGHT.remove(player.getUniqueId());
                TEMPORARY_FLIGHT.add(player.getUniqueId());
                player.setAllowFlight(true);
                sender.sendMessage(flyingConfirm);
                return true;
            }
            if (SESSION_FLIGHT.remove(player.getUniqueId())) {
                removeFlight(player);
                sender.sendMessage(Component.text("You are no longer flying!", NamedTextColor.GREEN));
                return true;
            }
            sender.sendMessage(flyingConfirm);
            SESSION_FLIGHT.add(player.getUniqueId());
            player.setAllowFlight(true);
            return true;
        default:
            break;
        }
        return false;
    }

    private void removeFlight(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        GRACEFUL_LAND.put(player.getUniqueId(), System.currentTimeMillis());
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    @Override
    public void onDisable() {
        DataSource.getData().save(getDataFolder());
    }

    @Override
    public void onEnable() {
        // Load data and configuration
        saveDefaultConfig();
        FileConfiguration bukkitCfg = getConfig();
        Configuration config = new Configuration(bukkitCfg.getInt("scoreboard-refresh"), 
                bukkitCfg.getInt("tick-interval"),
                bukkitCfg.getInt("travel-interval"),
                bukkitCfg.getInt("autosave-interval"),
                bukkitCfg.getInt("claim-size"),
                bukkitCfg.getDouble("tick-nearby-chance"),
                bukkitCfg.getBoolean("enable-claim-fly"));
        DataSource.setConfiguration(config);
        DataSource.setData(new PresenceData(config.getClaimSize(), config.getTickNearbyChunksChance()));
        DataSource.getData().load(getLogger()::warning, getDataFolder());

        // Register plugin integrations
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", false, getClassLoader());
            new PresencePlaceholders(this).register();
        } catch (ClassNotFoundException ignore) {}

        // Register bukkit events
        Bukkit.getPluginManager().registerEvents(new PresenceListener(), this);

        // Register tasks
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            PresenceData data = DataSource.getData();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null) {
                    continue;
                }
                Location position = player.getLocation();
                World world = position.getWorld();
                if (world == null) {
                    continue;
                }
                data.tick(player.getUniqueId(), world.getUID(), position.getBlockX() >> 4, position.getBlockZ() >> 4);
            }
        }, config.getClaimTickInterval(), config.getClaimTickInterval());
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                if (p == null) {
                    // Please the eclipse gods
                    continue;
                }
                if (SCOREBOARD_SUBSCRIBERS.containsKey(p.getUniqueId())) {
                    updateSb(p);
                }
            }
        }, config.getScoreboardRefreshInterval(), config.getScoreboardRefreshInterval());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void playerJoin(PlayerJoinEvent evt) {
                // reset scoreboard
                Score s = SCOREBOARD_CLAIM_OWNER.get(evt.getPlayer().getUniqueId());
                if (s != null) {
                    Scoreboard sb = s.getScoreboard();
                    if (sb != null) {
                        evt.getPlayer().setScoreboard(sb);
                    } else {
                        SCOREBOARD_CLAIM_OWNER.remove(evt.getPlayer().getUniqueId());
                    }
                }
            }

            @EventHandler
            public void playerHurt(EntityDamageEvent evt) {
                if (evt.getEntity() instanceof Player && evt.getCause() == DamageCause.FALL) {
                    long landingPoint = GRACEFUL_LAND.getOrDefault(evt.getEntity().getUniqueId(), -1L) + 10000;
                    if (landingPoint > System.currentTimeMillis()) {
                        GRACEFUL_LAND.remove(evt.getEntity().getUniqueId());
                        evt.setCancelled(true);
                    }
                }
            }
        }, this);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            PresenceData data = DataSource.getData();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                Location loc = p.getLocation();
                int chunkX = loc.getBlockX() >> 4;
                int chunkY = loc.getBlockZ() >> 4;
                World bukkitWorld = loc.getWorld();
                if (bukkitWorld == null) {
                    getLogger().warning("Presence Seizure.");
                    return;
                }
                UUID world = bukkitWorld.getUID();
                UUID oldClaim = PLAYER_LOCATIONS.get(p.getUniqueId());
                Map.Entry<UUID, Integer> newClaim = data.getOwner(world, chunkX, chunkY);
                if (newClaim == null) {
                    // now in the wild
                    if (oldClaim != null) {
                        // ... but was not in the wild before!
                        if (TEMPORARY_FLIGHT.remove(p.getUniqueId()) || SESSION_FLIGHT.remove(p.getUniqueId())) {
                            removeFlight(p);
                        }
                        sendActionbarMessage(p, "You are now in the wild.", NamedTextColor.GREEN);
                        PLAYER_LOCATIONS.put(p.getUniqueId(), null);
                    } else {
                        // Same state -> do nothing
                    }
                } else if (oldClaim != null) {
                    UUID newClaimId = newClaim.getKey();
                    PLAYER_LOCATIONS.put(p.getUniqueId(), newClaimId);
                    if (newClaimId.equals(oldClaim)) {
                        // Same state -> do nothing
                    } else if (newClaimId.equals(p.getUniqueId())) {
                        // Entered the own claim
                        sendActionbarMessage(p, "You are now entering your claim.", NamedTextColor.DARK_GREEN);
                    } else {
                        // Entered different claim
                        OfflinePlayer claimOwner = Bukkit.getOfflinePlayer(newClaimId);
                        if (!data.isTrusted(newClaimId, p.getUniqueId())) {
                            if (TEMPORARY_FLIGHT.remove(p.getUniqueId()) || SESSION_FLIGHT.remove(p.getUniqueId())) {
                                removeFlight(p);
                            }
                            sendActionbarMessage(p, "You are now entering the claim of " + claimOwner.getName() + ".", NamedTextColor.YELLOW);
                        } else {
                            sendActionbarMessage(p, "You are now entering the claim of " + claimOwner.getName() + ".", NamedTextColor.DARK_BLUE);
                        }
                    }
                } else {
                    // Was in wild before, but now it is not anymore
                    UUID newClaimId = newClaim.getKey();
                    PLAYER_LOCATIONS.put(p.getUniqueId(), newClaimId);
                    if (newClaimId.equals(p.getUniqueId())) {
                        // Entered the own claim
                        sendActionbarMessage(p, "You are now entering your claim.", NamedTextColor.DARK_GREEN);
                    } else {
                        // Entered different claim
                        OfflinePlayer claimOwner = Bukkit.getOfflinePlayer(newClaimId);
                        if (!data.isTrusted(newClaimId, p.getUniqueId())) {
                            if (TEMPORARY_FLIGHT.remove(p.getUniqueId()) || SESSION_FLIGHT.remove(p.getUniqueId())) {
                                removeFlight(p);
                            }
                            sendActionbarMessage(p, "You are now entering the claim of " + claimOwner.getName() + ".", NamedTextColor.YELLOW);
                        } else {
                            sendActionbarMessage(p, "You are now entering the claim of " + claimOwner.getName() + ".", NamedTextColor.DARK_BLUE);
                        }
                    }
                }
            }
        }, config.getClaimTravelInterval(), config.getClaimTravelInterval());
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            DataSource.getData().save(getDataFolder());
        }, config.getAutosaveInterval(), config.getAutosaveInterval());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("claims")) {
            if (args.length > 1) {
                return null;
            }
            List<String> sList = new ArrayList<>(Arrays.asList("togglesb", "help", "map", "trust", "untrust"));
            if (args.length == 1) {
                sList.removeIf(s -> !s.startsWith(args[0]));
            }
            return sList;
        }
        return null;
    }

    private void printMap(CommandSender sender) {
        if (sender instanceof Player) {
            Location loc = ((Player) sender).getLocation();
            int chunkX = loc.getBlockX() >> 4;
            int chunkY = loc.getBlockZ() >> 4;
            World bukkitWorld = loc.getWorld();
            if (bukkitWorld == null) {
                sender.sendMessage(ChatColor.RED + "Server is having a seizure.");
                return;
            }
            UUID world = bukkitWorld.getUID();
            UUID plyr = ((Player) sender).getUniqueId();
            PresenceData data = DataSource.getData();
            sender.sendMessage(ChatColor.GOLD + " + " + ChatColor.RESET + "= this "
                    + ChatColor.GRAY + " + " + ChatColor.RESET + "= unclaimed "
                    + ChatColor.DARK_GREEN + " + " + ChatColor.RESET + "= yours "
                    + ChatColor.DARK_BLUE + " + " + ChatColor.RESET + "= trusted "
                    + ChatColor.RED + " + " + ChatColor.RESET + "= others");
            for (int yDelta = -4; yDelta < 5; yDelta++) {
                StringBuilder ln = new StringBuilder(80);
                for (int xDelta = -14; xDelta < 14; xDelta++) {
                    if (xDelta == 0 && yDelta == 0) {
                        ln.append(ChatColor.GOLD + " +");
                        continue;
                    }
                    Map.Entry<UUID, Integer> leader = data.getOwner(world, chunkX + xDelta, chunkY + yDelta);
                    if (leader == null) {
                        ln.append(ChatColor.GRAY + " +");
                    } else if (leader.getKey().equals(plyr)) {
                        ln.append(ChatColor.DARK_GREEN + " +");
                    } else if (data.isTrusted(leader.getKey(), plyr)) {
                        ln.append(ChatColor.DARK_BLUE + " +");
                    } else {
                        ln.append(ChatColor.RED + " +");
                    }
                }
                String line = ln.toString();
                if (line == null) {
                    throw new IllegalStateException();
                }
                sender.sendMessage(line);
            }
        }
    }

    void updateSb(Player player) {
        UUID playerUID = player.getUniqueId();
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkY = loc.getBlockZ() >> 4;
        UUID world = player.getWorld().getUID();
        PresenceData presenceData = DataSource.getData();
        Map.Entry<UUID, Integer> leader = presenceData.getOwner(world, chunkX, chunkY);
        Score claimownerPresence = SCOREBOARD_CLAIM_OWNER.get(playerUID);
        Score ownPresence = SCOREBOARD_CLAIM_SELF.get(playerUID);
        Score successorPresence = SCOREBOARD_CLAIM_SUCCESSOR.get(playerUID);
        if (leader == null) {
            claimownerPresence.setScore(0);
            ownPresence.setScore(0);
            successorPresence.setScore(0);
            return;
        }
        Map.Entry<UUID, Integer> successor = presenceData.getSuccessor(world, chunkX, chunkY);
        claimownerPresence.setScore(leader.getValue());
        if (leader.getKey().equals(playerUID)) {
            ownPresence.setScore(leader.getValue());
        } else {
            ownPresence.setScore(presenceData.getPresence(playerUID, world, chunkX, chunkY));
        }
        if (successor == null) {
            successorPresence.setScore(0);
        } else {
            successorPresence.setScore(successor.getValue());
        }
    }
}
