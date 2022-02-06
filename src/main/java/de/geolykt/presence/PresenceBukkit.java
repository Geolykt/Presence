package de.geolykt.presence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
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
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import de.geolykt.presence.common.Configuration;
import de.geolykt.presence.common.DataSource;
import de.geolykt.presence.common.PermissionMatrix;
import de.geolykt.presence.common.PlayerRecord;
import de.geolykt.presence.common.PresenceData;

public class PresenceBukkit extends JavaPlugin {

    // TODO dynmap integration

    private static final Map<UUID, UUID> PLAYER_LOCATIONS = new HashMap<>(); // For intelligent claim passing
    private static final Map<UUID, Score> SCOREBOARD_CLAIM_OWNER = new HashMap<>(); // For intelligent caching
    private static final Map<UUID, Score> SCOREBOARD_CLAIM_SELF = new HashMap<>();
    private static final Map<UUID, Score> SCOREBOARD_CLAIM_SUCCESSOR = new HashMap<>();
    private static final Map<UUID, Scoreboard> SCOREBOARD_SUBSCRIBERS = new HashMap<>();
    private static final Map<UUID, String> USER_NAME_CACHE = new ConcurrentHashMap<>();

    private static final Collection<UUID> TEMPORARY_FLIGHT = new HashSet<>();
    private static final Collection<UUID> SESSION_FLIGHT = new HashSet<>();
    private static final Map<UUID, Long> GRACEFUL_LAND = new HashMap<>();

    @NotNull
    private static final JoinConfiguration SPACE_WITH_SPACE_SUFFIX = JoinConfiguration.builder()
            .suffix(Component.space()).separator(Component.space()).build();

    private boolean successfullLoad = false;

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
        PlayerRecord leader = presenceData.getOwner(world, chunkX, chunkY);
        PlayerRecord successor = presenceData.getSuccessor(world, chunkX, chunkY);
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
        claimownerPresence.setScore(leader.score().get());
        if (leader.getPlayer().equals(playerUID)) {
            ownPresence.setScore(leader.score().get());
        } else {
            ownPresence.setScore(presenceData.getPresence(playerUID, world, chunkX, chunkY));
        }
        if (successor == null) {
            successorPresence.setScore(0);
        } else {
            successorPresence.setScore(successor.score().get());
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
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " perm" + ChatColor.RED + " : " + ChatColor.WHITE + "Manage the permissions of your claims.");
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
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (player == null || (!player.hasPlayedBefore() && !player.isOnline())) {
                    sender.sendMessage(Component.text("The selected player did not play on this server (yet)!", NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("You need to be a player for that!", NamedTextColor.RED));
                    return true;
                }
                DataSource.getData().getChunkGroupManager().addTrustedPlayer(((Player)sender).getUniqueId(), player.getUniqueId());
                sender.sendMessage(Component.text("You are now trusting " + player.getName() + ".", NamedTextColor.GREEN));
                return true;
            }
            case "untrust": {
                if (args.length == 1) {
                    sender.sendMessage(Component.text("You have to specify the target player!", NamedTextColor.RED));
                    return true;
                }
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (player == null || !player.hasPlayedBefore() && !player.isOnline()) {
                    sender.sendMessage(Component.text("The selected player did not play on this server (or does not exist)!", NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("You need to be a player to be able to do that!", NamedTextColor.RED));
                    return true;
                }
                UUID truster = ((Player)sender).getUniqueId();
                boolean changed = DataSource.getData().getChunkGroupManager().removeTrustedPlayer(truster, player.getUniqueId());
                if (changed) {
                    sender.sendMessage(Component.text("You are no longer trusting " + player.getName() + ".", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("You are not yet trusting that player!", NamedTextColor.RED));
                }
                return true;
            }
            case "perm":
            case "perms":
            case "permission":
            case "permissions":
                processPermissions(sender, args);
                return true;
            default:
                sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
                return true;
            }
            // Break irrelevant as all other branches return
        case "claimfly": {
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
            PlayerRecord owner = DataSource.getData().getOwner(world, chunkX, chunkY);
            UUID ownerUID = owner == null ? null : owner.getPlayer();
            if (ownerUID == null || !(ownerUID.equals(player.getUniqueId()) || DataSource.getData().getChunkGroupManager().isTrusted(ownerUID, player.getUniqueId()))) {
                sender.sendMessage(Component.text("You are not in your claim!", NamedTextColor.RED));
                return true;
            }
            if (TEMPORARY_FLIGHT.remove(player.getUniqueId())) {
                removeFlight(player);
                sender.sendMessage(Component.text("You are no longer flying!", NamedTextColor.GREEN));
                return true;
            }
            Component flyingConfirm = Component.text("You are now able to fly in your claim!", NamedTextColor.GREEN);
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
        }
        case "forceclaim": {
            if (!sender.isOp()) {
                return true;
            }
            Player p = (Player) sender;
            UUID player = p.getUniqueId();
            UUID world = p.getWorld().getUID();
            int cx = p.getChunk().getX();
            int cy = p.getChunk().getZ();
            for (int i = 0; i < Integer.parseInt(args[0]); i++) {
                DataSource.getData().tick(player, world, cx, cy);
            }
            return true;
        }
        default:
            break;
        }
        return false;
    }

    @NotNull
    @Contract(value = "_, !null -> new; _, null -> fail", pure = true)
    private Component texifyPermissionBitfield(int permissions, @NotNull String commandPrefix) {
        boolean owner = (permissions & PermissionMatrix.PERSON_OWNER) != 0;
        boolean trusted = (permissions & PermissionMatrix.PERSON_TRUSTED) != 0;
        boolean visitor = (permissions & PermissionMatrix.PERSON_STRANGER) != 0;
        Component ownerComp;
        Component trustedComp;
        Component visitorComp;
        if (owner) {
            ownerComp = Component.text('O', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " owner deny"))
                    .hoverEvent(HoverEvent.showText(Component.text("Current enabled for you.")));
        } else {
            ownerComp = Component.text('O', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " owner allow"))
                    .hoverEvent(HoverEvent.showText(Component.text("Current disabled for you.")));
        }
        if (trusted) {
            trustedComp = Component.text('T', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " trusted deny"))
                    .hoverEvent(HoverEvent.showText(Component.text("Current enabled for trusted people.")));
        } else {
            trustedComp = Component.text('T', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " trusted allow"))
                    .hoverEvent(HoverEvent.showText(Component.text("Current disabled for trusted people.")));
        }
        if (visitor) {
            visitorComp = Component.text('V', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " visitor deny"))
                    .hoverEvent(HoverEvent.showText(Component.text("Current allowed for visitors.")));
        } else {
            visitorComp = Component.text('V', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " visitor allow"))
                    .hoverEvent(HoverEvent.showText(Component.text("Current disabled for visitors.")));
        }
        return Component.join(SPACE_WITH_SPACE_SUFFIX, ownerComp, trustedComp, visitorComp);
    }

    @Contract(value = "null, _, _, _ -> fail", pure = true)
    @Nullable
    private PermissionMatrix alter(@NotNull PermissionMatrix perms, @NotNull String name, int person, boolean allow) {
        switch(name.toLowerCase(Locale.ROOT)) {
        case "attack":
            return perms.alterAttack(person, allow);
        case "attacknamed":
            return perms.alterAttackNamed(person, allow);
        case "build":
            return perms.alterBuild(person, allow);
        case "destroy":
            return perms.alterDestroy(person, allow);
        case "harvest":
            return perms.alterHarvestCrops(person, allow);
        case "interact":
            return perms.alterInteract(person, allow);
        case "interactentity":
            return perms.alterInteractEntity(person, allow);
        case "trample":
            return perms.alterTrample(person, allow);
        default:
            return null;
        }
    }

    private void processPermissions(@NotNull CommandSender sender, @NotNull String[] args) {
        Player p;
        if (sender instanceof Player var10001) {
            p = var10001;
        } else {
            sender.sendMessage(Component.text("Only players can manage their permissions!", NamedTextColor.RED));
            return;
        }
        if (args.length != 1 && args.length != 6) {
            sender.sendMessage(Component.text("Syntax is: /claims " + args[0] + " set global|<group> <action> <person> allow|deny", NamedTextColor.RED));
            return;
        }
        PermissionMatrix perms = DataSource.getData().getChunkGroupManager().getPermissionMatrix(p.getUniqueId(), null);

        if (args.length == 6) {
            if (args[1].equals("set")) {
                boolean allow = args[5].equalsIgnoreCase("allow");
                if (!allow && !args[5].equalsIgnoreCase("deny")) {
                    sender.sendMessage(Component.text("Syntax is: /claims " + args[0] + " set global|<group> <action> <person> allow|deny", NamedTextColor.RED));
                    return;
                }
                int person;
                switch (args[4].toLowerCase(Locale.ROOT)) {
                case "owner":
                case "self":
                    person = PermissionMatrix.PERSON_OWNER;
                    break;
                case "trusted":
                case "friend":
                case "ally":
                    person = PermissionMatrix.PERSON_TRUSTED;
                    break;
                case "other":
                case "visitor":
                case "foreign":
                case "stranger":
                    person = PermissionMatrix.PERSON_STRANGER;
                    break;
                default:
                    sender.sendMessage(Component.text("Unknown person: " + args[4] + ", should be either owner, trusted or visitor.", NamedTextColor.RED));
                    return;
                }
                if (args[2].equals("global")) {
                   perms = alter(perms, args[3], person, allow);
                   if (perms == null) {
                       sender.sendMessage(Component.text("Unknown action: " + args[3] + ".", NamedTextColor.RED));
                       return;
                   }
                   DataSource.getData().getChunkGroupManager().setPlayerDefaultPermissions(p.getUniqueId(), perms);
                }
            }
        }
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.empty());
        sender.sendMessage(texifyPermissionBitfield(perms.getAttackBitfield(), "/claims perm set global attack").append(Component.text("Attack", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getAttackNamedBitfield(), "/claims perm set global attackNamed").append(Component.text("Attack Named Entities", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getBuildBitfield(), "/claims perm set global build").append(Component.text("Build blocks", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getDestroyBitfield(), "/claims perm set global destroy").append(Component.text("Destroy blocks", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getHarvestCropsBitfield(), "/claims perm set global harvest").append(Component.text("Harvest crops", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getInteractBlockBitfield(), "/claims perm set global interact").append(Component.text("Interact with blocks", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getInteractEntityBitfield(), "/claims perm set global interactEntity").append(Component.text("Interact with entities", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getTrampleBitfield(), "/claims perm set global trample").append(Component.text("Trample farmland", NamedTextColor.DARK_GRAY)));
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
        if (successfullLoad) {
            DataSource.getData().save(getDataFolder());
        }
    }

    @Override
    public void onEnable() {
        // Load data and configuration
        saveDefaultConfig();
        FileConfiguration bukkitCfg = getConfig();
        Set<Material> harvestableCrops = new HashSet<>();
        for (String s : bukkitCfg.getStringList("harvestable-crops")) {
            if (s.codePointAt(0) != '#') {
                String var10001 = s.substring(1);
                if (var10001 == null) {
                    throw new NullPointerException(s);
                }
                NamespacedKey key = NamespacedKey.fromString(var10001);
                if (key == null) {
                    throw new IllegalStateException("Invalid namespaced key \"" + s + "\" is the harvestable-crops list.");
                }
                Tag<@NotNull Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    // Try it again with the item registry
                    tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                }
                if (tag == null) {
                    getSLF4JLogger().error("The tag \"{}\" in the harvestable-crops list does not exist.", var10001);
                } else {
                    harvestableCrops.addAll(tag.getValues());
                }
            } else {
                Material mat = Material.matchMaterial(s);
                if (mat == null) {
                    getSLF4JLogger().error("There is no material \"{}\", even though it was set in the harvestable-crops list.", s);
                } else {
                    harvestableCrops.add(mat);
                }
            }
        }
        Configuration config = new Configuration(bukkitCfg.getInt("scoreboard-refresh"), 
                bukkitCfg.getInt("tick-interval"),
                bukkitCfg.getInt("travel-interval"),
                bukkitCfg.getInt("autosave-interval"),
                bukkitCfg.getDouble("tick-nearby-chance"),
                bukkitCfg.getBoolean("enable-claim-fly"), harvestableCrops);
        DataSource.setConfiguration(config);
        DataSource.setData(new PresenceData(config.getTickNearbyChunksChance()));

        try {
            DataSource.getData().load(getDataFolder());
        } catch (Throwable t) {
            t.printStackTrace();
            getSLF4JLogger().error("Failed to load plugin data. Due to the importance of this plugin on the server, the server"
                    + " is forcefully shut down.");
            Bukkit.shutdown();
            throw new IllegalStateException("Plugin data cannot be loaded.", t);
        }

        // Register plugin integrations
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", false, getClassLoader());
            new PresencePlaceholders(this).register();
        } catch (ClassNotFoundException ignore) {}

        // Register bukkit events
        Bukkit.getPluginManager().registerEvents(new PresenceListener(this), this);

        // Register tasks
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            // perhaps we can do this async, but given the relative speed of this operation, this is not
            // really required
            PresenceData data = DataSource.getData();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) {
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
                UUID world = p.getWorld().getUID();
                UUID oldClaim = PLAYER_LOCATIONS.get(p.getUniqueId());
                PlayerRecord newClaim = data.getOwner(world, chunkX, chunkY);
                if (newClaim == null) {
                    // now in the wild
                    if (oldClaim != null) {
                        // ... but was not in the wild before!
                        if (TEMPORARY_FLIGHT.remove(p.getUniqueId()) || SESSION_FLIGHT.contains(p.getUniqueId())) {
                            removeFlight(p);
                        }
                        sendActionbarMessage(p, "You are now in the wild.", NamedTextColor.GREEN);
                        PLAYER_LOCATIONS.put(p.getUniqueId(), null);
                    } else {
                        // Same state -> do nothing
                    }
                } else if (oldClaim != null) {
                    UUID newClaimOwner = newClaim.getPlayer();
                    PLAYER_LOCATIONS.put(p.getUniqueId(), newClaimOwner);
                    if (newClaimOwner.equals(oldClaim)) {
                        // Same state -> do nothing
                    } else if (newClaimOwner.equals(p.getUniqueId())) {
                        // Entered the own claim
                        sendActionbarMessage(p, "You are now entering your claim.", NamedTextColor.DARK_GREEN);
                    } else {
                        // Entered different claim
                        OfflinePlayer claimOwner = Bukkit.getOfflinePlayer(newClaimOwner);
                        if (!data.getChunkGroupManager().isTrusted(newClaimOwner, p.getUniqueId())) {
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
                    UUID newClaimId = newClaim.getPlayer();
                    PLAYER_LOCATIONS.put(p.getUniqueId(), newClaimId);
                    if (newClaimId.equals(p.getUniqueId())) {
                        // Entered the own claim
                        if (SESSION_FLIGHT.contains(p.getUniqueId())) {
                            // Regain flying powers
                            p.setAllowFlight(true);
                        }
                        sendActionbarMessage(p, "You are now entering your claim.", NamedTextColor.DARK_GREEN);
                    } else {
                        // Entered different claim
                        OfflinePlayer claimOwner = Bukkit.getOfflinePlayer(newClaimId);
                        if (!data.getChunkGroupManager().isTrusted(newClaimId, p.getUniqueId())) {
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

        successfullLoad = true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("claims")) {
            if (args.length > 1) {
                return null;
            }
            List<String> sList = new ArrayList<>(Arrays.asList("togglesb", "help", "map", "trust", "untrust", "perm"));
            if (args.length == 1) {
                sList.removeIf(s -> !s.startsWith(args[0]));
            }
            return sList;
        }
        return null;
    }

    @NotNull
    private String getPlayerName(@NotNull UUID player) {
        String cname = USER_NAME_CACHE.get(player);
        if (cname != null) {
            return cname;
        }

        OfflinePlayer offlinePlayer =  Bukkit.getOfflinePlayer(player);

        String name = offlinePlayer.getName();
        if (name == null) {
            name = "unknown";
        }
        USER_NAME_CACHE.put(player, name);
        return name;
    }

    private void printMap(CommandSender sender) {
        if (sender instanceof Player player) {
            Location loc = player.getLocation();
            int chunkX = loc.getBlockX() >> 4;
            int chunkY = loc.getBlockZ() >> 4;
            UUID world = player.getWorld().getUID();
            UUID plyr = player.getUniqueId();
            PresenceData data = DataSource.getData();

            for (int yDelta = -5; yDelta < 5; yDelta++) {
                Component comp = Component.empty();
                for (int xDelta = -14; xDelta < 14; xDelta++) {
                    PlayerRecord leader = data.getOwner(world, chunkX + xDelta, chunkY + yDelta);

                    TextComponent.Builder chunk = Component.text();
                    chunk.content(" +");

                    String location = "Chunk " + (chunkX + xDelta) + "/" + (chunkY + yDelta);
                    String ownerName;
                    String type;
                    TextColor colorCoding;

                    UUID owner = leader == null ? null : leader.getPlayer();
                    if (owner == null) {
                        type = "Unclaimed";
                        ownerName = "None";
                        colorCoding = NamedTextColor.GRAY;
                        chunk.color(NamedTextColor.GRAY);
                    } else {
                        ownerName = getPlayerName(owner);

                        if (owner.equals(plyr)) {
                            type = "Owned";
                            colorCoding = NamedTextColor.DARK_GREEN;
                        } else if (data.getChunkGroupManager().isTrusted(owner, plyr)) {
                            colorCoding = NamedTextColor.DARK_BLUE;
                            type = "Trusted";
                        } else {
                            colorCoding = NamedTextColor.RED;
                            type = "Other";
                        }
                    }

                    if (xDelta == 0 && yDelta == 0) {
                        colorCoding = NamedTextColor.GOLD;
                        type = "This chunk";
                    }

                    chunk.color(colorCoding);
                    chunk.hoverEvent(HoverEvent.showText(Component.text(location, NamedTextColor.YELLOW, TextDecoration.BOLD)
                            .append(Component.newline()).append(Component.text("Owner: " + ownerName, colorCoding))
                            .append(Component.newline()).append(Component.text(type, colorCoding))));

                    comp = comp.append(chunk);
                }
                sender.sendMessage(comp);
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
        PlayerRecord leader = presenceData.getOwner(world, chunkX, chunkY);
        Score claimownerPresence = SCOREBOARD_CLAIM_OWNER.get(playerUID);
        Score ownPresence = SCOREBOARD_CLAIM_SELF.get(playerUID);
        Score successorPresence = SCOREBOARD_CLAIM_SUCCESSOR.get(playerUID);
        if (leader == null) {
            claimownerPresence.setScore(0);
            ownPresence.setScore(0);
            successorPresence.setScore(0);
            return;
        }
        PlayerRecord successor = presenceData.getSuccessor(world, chunkX, chunkY);
        claimownerPresence.setScore(leader.score().get());
        if (leader.getPlayer().equals(playerUID)) {
            ownPresence.setScore(leader.score().get());
        } else {
            ownPresence.setScore(presenceData.getPresence(playerUID, world, chunkX, chunkY));
        }
        if (successor == null) {
            successorPresence.setScore(0);
        } else {
            successorPresence.setScore(successor.score().get());
        }
    }
}
