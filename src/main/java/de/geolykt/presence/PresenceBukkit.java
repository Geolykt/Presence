package de.geolykt.presence;

import java.io.File;
import java.io.IOException;
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

import de.geolykt.presence.common.ChunkGroup;
import de.geolykt.presence.common.ChunkGroupManager;
import de.geolykt.presence.common.Configuration;
import de.geolykt.presence.common.DataSource;
import de.geolykt.presence.common.PermissionMatrix;
import de.geolykt.presence.common.PresenceData;
import de.geolykt.presence.common.util.ElementAlreadyExistsException;
import de.geolykt.presence.common.util.PlayerAttachedScore;
import de.geolykt.presence.common.util.WorldPosition;
import de.geolykt.presence.i18n.I18NKey;
import de.geolykt.presence.i18n.LocalisationContainer;

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

    @NotNull
    private final LocalisationContainer i18n = new LocalisationContainer();
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
        PlayerAttachedScore leader = presenceData.getOwner(world, chunkX, chunkY);
        PlayerAttachedScore successor = presenceData.getSuccessor(world, chunkX, chunkY);
        Locale locale = player.locale();
        Objective objective = scoreboard.registerNewObjective("presence_claims", "dummy", Component.text(i18n.get(I18NKey.SCOREBOARD_TITLE, locale), NamedTextColor.YELLOW, TextDecoration.BOLD), RenderType.INTEGER);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setRenderType(RenderType.INTEGER);
        Score claimownerPresence = objective.getScore(i18n.get(I18NKey.SCOREBOARD_OWNER_PRESENCE, locale));
        Score ownPresence = objective.getScore(i18n.get(I18NKey.SCOREBOARD_USER_PRESENCE, locale));
        Score successorPresence = objective.getScore(i18n.get(I18NKey.SCOREBOARD_SUCCESSOR_PRESENCE, locale));
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
        Locale senderLocale;
        if (sender instanceof Player p) {
            senderLocale = p.locale();
        } else {
            senderLocale = Locale.ENGLISH;
            if (senderLocale == null) {
                throw new InternalError("Someone messed with the reflection API again");
            }
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
        case "claims":
            if (args.length == 0) {
                args = new @NotNull String[] {"help"}; // Because I could not be bothered to do this otherwise
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help":
                sender.sendMessage(ChatColor.YELLOW + i18n.get(I18NKey.TITLE_CLAIMS_HELP, senderLocale));
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " help" + ChatColor.RED + " : " + ChatColor.WHITE + i18n.get(I18NKey.INFO_CLAIMS_HELP, senderLocale));
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " togglesb" + ChatColor.RED + " : " + ChatColor.WHITE + i18n.get(I18NKey.INFO_CLAIMS_TOGGLESB, senderLocale));
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " map" + ChatColor.RED + " : " + ChatColor.WHITE + i18n.get(I18NKey.INFO_CLAIMS_MAP, senderLocale));
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " trust <player>" + ChatColor.RED + " : " + ChatColor.WHITE + i18n.get(I18NKey.INFO_CLAIMS_TRUST, senderLocale));
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " untrust <player>" + ChatColor.RED + " : " + ChatColor.WHITE + i18n.get(I18NKey.INFO_CLAIMS_UNTRUST, senderLocale));
                sender.sendMessage(ChatColor.GREEN + "/claims" + ChatColor.BLUE + " perm" + ChatColor.RED + " : " + ChatColor.WHITE + i18n.get(I18NKey.INFO_CLAIMS_PERM, senderLocale));
                return true;
            case "togglesb":
                if (sender instanceof Player) {
                    Player plyr = ((Player)sender);
                    UUID id = plyr.getUniqueId();
                    ScoreboardManager mgr = Bukkit.getScoreboardManager();
                    if (SCOREBOARD_SUBSCRIBERS.containsKey(id)) {
                        SCOREBOARD_SUBSCRIBERS.remove(id);
                        sender.sendMessage(Component.text(i18n.get(I18NKey.TOGGLESB_RESET, senderLocale), NamedTextColor.YELLOW));
                        plyr.setScoreboard(mgr.getMainScoreboard());
                    } else {
                        Scoreboard sb = mgr.getNewScoreboard();
                        SCOREBOARD_SUBSCRIBERS.put(id, sb);
                        initSb(plyr, sb);
                        plyr.setScoreboard(sb);
                        sender.sendMessage(Component.text(i18n.get(I18NKey.TOGGLESB_ENABLE, senderLocale), NamedTextColor.DARK_GREEN));
                    }
                }
                return true;
            case "map":
                printMap(sender);
                return true;
            case "trust": {
                if (args.length == 1) {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.CMD_UNSPECIFIED_TARGET, senderLocale), NamedTextColor.RED));
                    return true;
                }
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (player == null || (!player.hasPlayedBefore() && !player.isOnline())) {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.CMD_UNKNOWN_TARGET, senderLocale), NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("You need to be a player for that!", NamedTextColor.RED));
                    return true;
                }
                DataSource.getData().getChunkGroupManager().addTrustedPlayer(((Player)sender).getUniqueId(), player.getUniqueId());
                sender.sendMessage(Component.text(i18n.get(I18NKey.TRUST_SUCCESS, senderLocale, player.getName()), NamedTextColor.GREEN));
                return true;
            }
            case "untrust": {
                if (args.length == 1) {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.CMD_UNSPECIFIED_TARGET, senderLocale), NamedTextColor.RED));
                    return true;
                }
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (player == null || !player.hasPlayedBefore() && !player.isOnline()) {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.CMD_UNKNOWN_TARGET, senderLocale), NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("You need to be a player to be able to do that!", NamedTextColor.RED));
                    return true;
                }
                UUID truster = ((Player)sender).getUniqueId();
                boolean changed = DataSource.getData().getChunkGroupManager().removeTrustedPlayer(truster, player.getUniqueId());
                if (changed) {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.UNTRUST_SUCCESS, senderLocale, player.getName()), NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.UNTRUST_NOT_TRUSTED, senderLocale), NamedTextColor.RED));
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
                sender.sendMessage(Component.text(i18n.get(I18NKey.CLAIMS_UNKNOWN_CMD, senderLocale), NamedTextColor.RED));
                return true;
            }
            // Break irrelevant as all other branches return
        case "claimfly": {
            if (!DataSource.getConfiguration().allowsFlight()) {
                sender.sendMessage(Component.text(i18n.get(I18NKey.CLAIMFLY_NOT_ENABLED, senderLocale), NamedTextColor.RED));
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
            PlayerAttachedScore owner = DataSource.getData().getOwner(world, chunkX, chunkY);
            UUID ownerUID = owner == null ? null : owner.getPlayer();
            if (ownerUID == null || !(ownerUID.equals(player.getUniqueId()) || DataSource.getData().getChunkGroupManager().isTrusted(ownerUID, player.getUniqueId()))) {
                sender.sendMessage(Component.text(i18n.get(I18NKey.CLAIMFLY_NOT_IN_CLAIM, senderLocale), NamedTextColor.RED));
                return true;
            }
            if (TEMPORARY_FLIGHT.remove(player.getUniqueId())) {
                removeFlight(player);
                sender.sendMessage(Component.text(i18n.get(I18NKey.CLAIMFLY_DISABLE_FLIGHT, senderLocale), NamedTextColor.GREEN));
                return true;
            }
            Component flyingConfirm = Component.text(i18n.get(I18NKey.CLAIMFLY_ENABLE_FLIGHT, senderLocale), NamedTextColor.GREEN);
            if (args.length == 2 && args[1].equalsIgnoreCase("temporary")) {
                SESSION_FLIGHT.remove(player.getUniqueId());
                TEMPORARY_FLIGHT.add(player.getUniqueId());
                player.setAllowFlight(true);
                sender.sendMessage(flyingConfirm);
                return true;
            }
            if (SESSION_FLIGHT.remove(player.getUniqueId())) {
                removeFlight(player);
                sender.sendMessage(Component.text(i18n.get(I18NKey.CLAIMFLY_DISABLE_FLIGHT, senderLocale), NamedTextColor.GREEN));
                return true;
            }
            sender.sendMessage(flyingConfirm);
            SESSION_FLIGHT.add(player.getUniqueId());
            player.setAllowFlight(true);
            return true;
        }
        case "chunkgroups":
            if (sender instanceof Player player) {
                manageChunkGroups(player, args);
            } else {
                sender.sendMessage(Component.text("You must be a player in order to do this action.", NamedTextColor.RED));
            }
            return true;
        default:
            break;
        }
        return false;
    }

    private void manageChunkGroups(Player player, @NotNull String[] args) {
        Locale senderLocale = player.locale();
        ChunkGroupManager groupManager = DataSource.getData().getChunkGroupManager();
        if (args.length == 0) {
            Set<ChunkGroup> groups = groupManager.getOwnedGroups(player.getUniqueId());
            if (groups == null || groups.isEmpty()) {
                player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NO_GROUPS, senderLocale), NamedTextColor.RED)
                        .append(Component.text(i18n.get(I18NKey.CHUNKGROUPS_SUGGEST_CREATION, senderLocale), NamedTextColor.DARK_BLUE, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.suggestCommand("/chunkgroups create "))));
                return;
            }
            player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_LIST_HEADER, senderLocale), NamedTextColor.GREEN));
            for (ChunkGroup group : groups) {
                player.sendMessage(Component.text(group.getName(), NamedTextColor.DARK_AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/chunkgroups manage " + group.name()))
                        .append(Component.text(" (", NamedTextColor.GRAY))
                        .append(Component.text(group.claimedChunks().size(), NamedTextColor.GOLD))
                        .append(Component.text(")", NamedTextColor.GRAY)));
            }
            return;
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("manage")) {
                args = new @NotNull String[] {args[1]};
            } else if (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("new")) {
                if (args[1].equalsIgnoreCase("global") || args[1].equalsIgnoreCase("here")
                        || args[1].equalsIgnoreCase("new") || args[1].equalsIgnoreCase("create")
                        || args[1].equalsIgnoreCase("assign") || args[1].equalsIgnoreCase("manage")
                        || args[1].equalsIgnoreCase("unassign") || args[1].equalsIgnoreCase("help")) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_KEYWORD_NAME, senderLocale), NamedTextColor.DARK_RED));
                    return;
                }
                ChunkGroup cgroup = DataSource.getData().getChunkGroupManager().getChunkGroup(player.getUniqueId(), args[1]);
                if (cgroup != null) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_DUPLICATE_NAME, senderLocale), NamedTextColor.DARK_RED));
                    return;
                }
                try {
                    cgroup = DataSource.getData().getChunkGroupManager().createChunkGroup(player.getUniqueId(), args[1]);
                } catch (ElementAlreadyExistsException e) {
                    // Unlikely to happen, but we want to be atomically safe, so this is required nonetheless
                    player.sendMessage(Component.text(i18n.get(I18NKey.CMD_INTERNAL_CONCURRENCY_ERROR, senderLocale), NamedTextColor.RED));
                    e.printStackTrace();
                    return;
                }
                player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_CREATE_SUCCESS, senderLocale), NamedTextColor.GREEN));
                return;
            } else if (args[0].equalsIgnoreCase("assign")) {
                ChunkGroup cgroup = DataSource.getData().getChunkGroupManager().getChunkGroup(player.getUniqueId(), args[1]);
                if (cgroup == null) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_A_GROUP, senderLocale), NamedTextColor.RED)
                            .append(Component.text(args[1], NamedTextColor.DARK_RED)));
                    return;
                }
                UUID world = player.getWorld().getUID();
                int chunkX = player.getLocation().getBlockX() >> 4;
                int chunkZ = player.getLocation().getBlockZ() >> 4;
                PlayerAttachedScore score = DataSource.getData().getOwner(world, chunkX, chunkZ);
                if (score == null || !score.getPlayer().equals(player.getUniqueId())) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_THE_OWNER_ASSIGN, senderLocale), NamedTextColor.DARK_RED));
                    return;
                }
                WorldPosition pos = new WorldPosition(world, PresenceData.hashPositions(chunkX, chunkZ));
                if (cgroup.claimedChunks().contains(pos)) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_ALREADY_ASSIGNED_ITSELF, senderLocale), NamedTextColor.RED));
                    return;
                }
                if (DataSource.getData().getChunkGroupManager().getGroupAt(pos) != null) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_ALREADY_ASSIGNED_OTHER, senderLocale), NamedTextColor.RED));
                    return;
                }
                if (!DataSource.getData().getChunkGroupManager().addChunk(cgroup, pos)) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CMD_INTERNAL_CONCURRENCY_ERROR, senderLocale), NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CMD_SUCCESS, senderLocale), NamedTextColor.GREEN));
                }
                return;
            }
        }

        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("here")) {
                Location loc = player.getLocation();
                WorldPosition pos = new WorldPosition(loc.getWorld().getUID(),
                        PresenceData.hashPositions(loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
                ChunkGroup cgroup = groupManager.getGroupAt(pos);
                if (cgroup == null) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_IN_GROUP, senderLocale), NamedTextColor.YELLOW));
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_SUGGEST_CREATION, senderLocale), NamedTextColor.GREEN));
                    return;
                } else {
                    String ownerName = Bukkit.getOfflinePlayer(cgroup.getOwner()).getName();
                    if (ownerName == null) {
                        ownerName = "null";
                    }
                    player.sendMessage(Component.text(" ==== ", NamedTextColor.DARK_PURPLE)
                            .append(Component.text(cgroup.getName(), NamedTextColor.YELLOW))
                            .append(Component.text(" ==== ", NamedTextColor.DARK_PURPLE)));
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_LIST_GROUP_OWNER, senderLocale))
                            .append(Component.text(ownerName, NamedTextColor.GOLD)));
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_LIST_GROUP_SIZE, senderLocale))
                            .append(Component.text(cgroup.claimedChunks().size(), NamedTextColor.GOLD)));
                    PermissionMatrix perms = cgroup.permissions();
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getAttackBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_ATTACK, senderLocale), NamedTextColor.DARK_GRAY)));
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getAttackNamedBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_ATTACK_NAMED, senderLocale), NamedTextColor.DARK_GRAY)));
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getBuildBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_BUILD, senderLocale), NamedTextColor.DARK_GRAY)));
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getDestroyBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_DESTORY, senderLocale), NamedTextColor.DARK_GRAY)));
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getHarvestCropsBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_HARVEST, senderLocale), NamedTextColor.DARK_GRAY)));
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getInteractBlockBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_INTERACT, senderLocale), NamedTextColor.DARK_GRAY)));
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getInteractEntityBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_INTERACT_ENTITY, senderLocale), NamedTextColor.DARK_GRAY)));
                    player.sendMessage(texifyPermissionBitfieldReadonly(perms.getTrampleBitfield(), senderLocale).append(Component.text(i18n.get(I18NKey.PERM_TRAMPLE, senderLocale), NamedTextColor.DARK_GRAY)));

                    Component comp;
                    if (perms.getExplosionsEnabled()) {
                        comp = Component.text(i18n.get(I18NKey.PERM_ENABLED, senderLocale), NamedTextColor.GRAY, TextDecoration.BOLD)
                                .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_EXPLOSION_ENABLED, senderLocale))));
                    } else {
                        comp = Component.text(i18n.get(I18NKey.PERM_DISABLED, senderLocale), NamedTextColor.RED, TextDecoration.BOLD)
                                .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_EXPLOSION_DISABLED, senderLocale))));
                    }
                    player.sendMessage(comp.append(Component.text(i18n.get(I18NKey.PERM_EXPLOSION, senderLocale), NamedTextColor.DARK_GRAY)));
                }
                return;
            } else if (args[0].equalsIgnoreCase("assign")) {
                player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_ASSIGN_INFO, senderLocale), NamedTextColor.AQUA));
                player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_ASSIGN_INVALID_SYNTAX, senderLocale), NamedTextColor.RED));
                return;
            } else if (args[0].equalsIgnoreCase("unassign")) {
                Location loc = player.getLocation();
                WorldPosition pos = new WorldPosition(loc.getWorld().getUID(),
                        PresenceData.hashPositions(loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
                ChunkGroup cgroup = groupManager.getGroupAt(pos);
                if (cgroup == null) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_IN_GROUP, senderLocale), NamedTextColor.YELLOW));
                    return;
                }
                if (!cgroup.owner().equals(player.getUniqueId())) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_THE_OWNER_UNASSIGN, senderLocale), NamedTextColor.YELLOW));
                    return;
                }
                if (groupManager.removeChunk(cgroup, pos)) {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CMD_SUCCESS, senderLocale), NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text(i18n.get(I18NKey.CMD_INTERNAL_CONCURRENCY_ERROR, senderLocale), NamedTextColor.RED));
                }
                return;
            }
            ChunkGroup cgroup = DataSource.getData().getChunkGroupManager().getChunkGroup(player.getUniqueId(), args[0]);
            if (cgroup == null) {
                player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_A_GROUP, senderLocale), NamedTextColor.RED)
                        .append(Component.text(args[0], NamedTextColor.DARK_RED)));
                return;
            } else {
                player.sendMessage(Component.text(" ==== ", NamedTextColor.DARK_PURPLE)
                        .append(Component.text(cgroup.getName(), NamedTextColor.YELLOW))
                        .append(Component.text(" ==== ", NamedTextColor.DARK_PURPLE)));
                player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_LIST_GROUP_OWNER, senderLocale))
                        .append(player.displayName().colorIfAbsent(NamedTextColor.GOLD)));
                player.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_LIST_GROUP_SIZE, senderLocale))
                        .append(Component.text(cgroup.claimedChunks().size(), NamedTextColor.GOLD)));
                PermissionMatrix perms = cgroup.permissions();
                player.sendMessage(texifyPermissionBitfield(perms.getAttackBitfield(), "/claims perm set " + cgroup.name() + " attack", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_ATTACK, senderLocale), NamedTextColor.DARK_GRAY)));
                player.sendMessage(texifyPermissionBitfield(perms.getAttackNamedBitfield(), "/claims perm set " + cgroup.name() + " attackNamed", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_ATTACK_NAMED, senderLocale), NamedTextColor.DARK_GRAY)));
                player.sendMessage(texifyPermissionBitfield(perms.getBuildBitfield(), "/claims perm set " + cgroup.name() + " build", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_BUILD, senderLocale), NamedTextColor.DARK_GRAY)));
                player.sendMessage(texifyPermissionBitfield(perms.getDestroyBitfield(), "/claims perm set " + cgroup.name() + " destroy", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_DESTORY, senderLocale), NamedTextColor.DARK_GRAY)));
                player.sendMessage(texifyPermissionBitfield(perms.getHarvestCropsBitfield(), "/claims perm set " + cgroup.name() + " harvest", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_HARVEST, senderLocale), NamedTextColor.DARK_GRAY)));
                player.sendMessage(texifyPermissionBitfield(perms.getInteractBlockBitfield(), "/claims perm set " + cgroup.name() + " interact", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_INTERACT, senderLocale), NamedTextColor.DARK_GRAY)));
                player.sendMessage(texifyPermissionBitfield(perms.getInteractEntityBitfield(), "/claims perm set " + cgroup.name() + " interactEntity", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_INTERACT_ENTITY, senderLocale), NamedTextColor.DARK_GRAY)));
                player.sendMessage(texifyPermissionBitfield(perms.getTrampleBitfield(), "/claims perm set " + cgroup.name() + " trample", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_TRAMPLE, senderLocale), NamedTextColor.DARK_GRAY)));
                Component comp;
                if (perms.getExplosionsEnabled()) {
                    comp = Component.text(i18n.get(I18NKey.PERM_ENABLED, senderLocale), NamedTextColor.GRAY, TextDecoration.BOLD)
                            .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_EXPLOSION_ENABLED, senderLocale))));
                } else {
                    comp = Component.text(i18n.get(I18NKey.PERM_DISABLED, senderLocale), NamedTextColor.RED, TextDecoration.BOLD)
                            .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_EXPLOSION_DISABLED, senderLocale))));
                }
                player.sendMessage(comp.clickEvent(ClickEvent.runCommand("/claims perm toggle " + cgroup.name() + " explosion"))
                        .append(Component.text(i18n.get(I18NKey.PERM_EXPLOSION, senderLocale), NamedTextColor.DARK_GRAY)));
            }
            return;
        }
    }

    @NotNull
    @Contract(value = "_, !null -> new; _, null -> fail", pure = true)
    private Component texifyPermissionBitfieldReadonly(int permissions, @NotNull Locale i18nLocale) {
        boolean owner = (permissions & PermissionMatrix.PERSON_OWNER) != 0;
        boolean trusted = (permissions & PermissionMatrix.PERSON_TRUSTED) != 0;
        boolean visitor = (permissions & PermissionMatrix.PERSON_STRANGER) != 0;
        Component ownerComp;
        Component trustedComp;
        Component visitorComp;
        if (owner) {
            ownerComp = Component.text('O', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_ENABLED_OWNER, i18nLocale))));
        } else {
            ownerComp = Component.text('O', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_DISABLED_OWNER, i18nLocale))));
        }
        if (trusted) {
            trustedComp = Component.text('T', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_ENABLED_TRUSTED, i18nLocale))));
        } else {
            trustedComp = Component.text('T', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_DISABLED_TRUSTED, i18nLocale))));
        }
        if (visitor) {
            visitorComp = Component.text('V', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_ENABLED_VISITOR, i18nLocale))));
        } else {
            visitorComp = Component.text('V', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_DISABLED_VISITOR, i18nLocale))));
        }
        // There was a TODO here, but I have no idea why
        return Component.join(SPACE_WITH_SPACE_SUFFIX, ownerComp, trustedComp, visitorComp);
    }

    @NotNull
    @Contract(value = "_, !null, !null -> new; _, null, _ -> fail; _, _, null -> fail", pure = true)
    private Component texifyPermissionBitfield(int permissions, @NotNull String commandPrefix, @NotNull Locale i18nLocale) {
        boolean owner = (permissions & PermissionMatrix.PERSON_OWNER) != 0;
        boolean trusted = (permissions & PermissionMatrix.PERSON_TRUSTED) != 0;
        boolean visitor = (permissions & PermissionMatrix.PERSON_STRANGER) != 0;
        Component ownerComp;
        Component trustedComp;
        Component visitorComp;
        if (owner) {
            ownerComp = Component.text('O', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " owner deny"))
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_ENABLED_OWNER_SELF, i18nLocale))));
        } else {
            ownerComp = Component.text('O', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " owner allow"))
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_DISABLED_OWNER_SELF, i18nLocale))));
        }
        if (trusted) {
            trustedComp = Component.text('T', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " trusted deny"))
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_ENABLED_TRUSTED, i18nLocale))));
        } else {
            trustedComp = Component.text('T', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " trusted allow"))
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_DISABLED_TRUSTED, i18nLocale))));
        }
        if (visitor) {
            visitorComp = Component.text('V', NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " visitor deny"))
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_ENABLED_VISITOR, i18nLocale))));
        } else {
            visitorComp = Component.text('V', NamedTextColor.GRAY, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand(commandPrefix + " visitor allow"))
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_DISABLED_VISITOR, i18nLocale))));
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
        Locale senderLocale = p.locale();
        if (args.length != 1 && args.length != 6 && args.length != 4) {
            sender.sendMessage(Component.text(i18n.get(I18NKey.PERM_CMD_SYNTAX_A, senderLocale, args[0]), NamedTextColor.RED));
            sender.sendMessage(Component.text(i18n.get(I18NKey.PERM_CMD_SYNTAX_B, senderLocale, args[0]), NamedTextColor.RED));
            return;
        }
        PermissionMatrix perms = DataSource.getData().getChunkGroupManager().getPermissionMatrix(p.getUniqueId(), null);
        String groupName = "global";

        if (args.length == 6) {
            if (args[1].equals("set")) {
                boolean allow = args[5].equalsIgnoreCase("allow");
                if (!allow && !args[5].equalsIgnoreCase("deny")) {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.PERM_CMD_SYNTAX_A, senderLocale, args[0]), NamedTextColor.RED));
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
                    sender.sendMessage(Component.text(i18n.get(I18NKey.PERM_UNKNOWN_PERSON, senderLocale, args[4]), NamedTextColor.RED));
                    return;
                }
                if (args[2].equals("global")) {
                   perms = alter(perms, args[3], person, allow);
                   if (perms == null) {
                       sender.sendMessage(Component.text(i18n.get(I18NKey.PERM_UNKNOWN_ACTION, senderLocale, args[3]), NamedTextColor.RED));
                       return;
                   }
                   DataSource.getData().getChunkGroupManager().setPlayerDefaultPermissions(p.getUniqueId(), perms);
                } else {
                    ChunkGroup group = DataSource.getData().getChunkGroupManager().getChunkGroup(p.getUniqueId(), args[2]);
                    if (group == null) {
                        sender.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_A_GROUP, senderLocale), NamedTextColor.RED)
                                .append(Component.text(args[2], NamedTextColor.DARK_RED)));
                        return;
                    }
                    while (true) {
                        perms = group.permissions();
                        PermissionMatrix oldPerm = perms;
                        perms = alter(perms, args[3], person, allow);
                        if (perms == null) {
                            sender.sendMessage(Component.text(i18n.get(I18NKey.PERM_UNKNOWN_ACTION, senderLocale, args[3]), NamedTextColor.RED));
                            return;
                        }
                        if (group.permissionRef().compareAndSet(oldPerm, perms)) {
                            break;
                        }
                    }
                    groupName = args[2];
                }
            }
        } else if (args.length == 4) {
            if (args[1].equals("toggle")) {
                if (!args[3].equals("explosion")) {
                    sender.sendMessage(Component.text(i18n.get(I18NKey.PERM_UNKNOWN_ACTION, senderLocale, args[3]), NamedTextColor.RED));
                    return;
                }
                if (args[2].equals("global")) {
                    perms = DataSource.getData().getChunkGroupManager().getPermissionMatrix(p.getUniqueId(), null);
                    perms = perms.alterExplosions(!perms.getExplosionsEnabled());
                    DataSource.getData().getChunkGroupManager().setPlayerDefaultPermissions(p.getUniqueId(), perms);
                 } else {
                     ChunkGroup group = DataSource.getData().getChunkGroupManager().getChunkGroup(p.getUniqueId(), args[2]);
                     if (group == null) {
                         sender.sendMessage(Component.text(i18n.get(I18NKey.CHUNKGROUPS_NOT_A_GROUP, senderLocale), NamedTextColor.RED)
                                 .append(Component.text(args[2], NamedTextColor.DARK_RED)));
                         return;
                     }
                     while (true) {
                         perms = group.permissions();
                         PermissionMatrix oldPerm = perms;
                         perms = perms.alterExplosions(!perms.getExplosionsEnabled());
                         if (group.permissionRef().compareAndSet(oldPerm, perms)) {
                             break;
                         }
                     }
                     groupName = args[2];
                 }
                sender.sendMessage(Component.text(i18n.get(I18NKey.CMD_SUCCESS, senderLocale), NamedTextColor.GREEN));
            }
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.empty());
        sender.sendMessage(texifyPermissionBitfield(perms.getAttackBitfield(), "/claims perm set " + groupName + " attack", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_ATTACK, senderLocale), NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getAttackNamedBitfield(), "/claims perm set " + groupName + " attackNamed", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_ATTACK_NAMED, senderLocale), NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getBuildBitfield(), "/claims perm set " + groupName + " build", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_BUILD, senderLocale), NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getDestroyBitfield(), "/claims perm set " + groupName + " destroy", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_DESTORY, senderLocale), NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getHarvestCropsBitfield(), "/claims perm set " + groupName + " harvest", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_HARVEST, senderLocale), NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getInteractBlockBitfield(), "/claims perm set " + groupName + " interact", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_INTERACT, senderLocale), NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getInteractEntityBitfield(), "/claims perm set " + groupName + " interactEntity", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_INTERACT_ENTITY, senderLocale), NamedTextColor.DARK_GRAY)));
        sender.sendMessage(texifyPermissionBitfield(perms.getTrampleBitfield(), "/claims perm set " + groupName + " trample", senderLocale).append(Component.text(i18n.get(I18NKey.PERM_TRAMPLE, senderLocale), NamedTextColor.DARK_GRAY)));
        Component comp;
        if (perms.getExplosionsEnabled()) {
            comp = Component.text(i18n.get(I18NKey.PERM_ENABLED, senderLocale), NamedTextColor.GRAY, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_EXPLOSION_ENABLED, senderLocale))));
        } else {
            comp = Component.text(i18n.get(I18NKey.PERM_DISABLED, senderLocale), NamedTextColor.RED, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(i18n.get(I18NKey.PERM_TOOLTIP_EXPLOSION_DISABLED, senderLocale))));
        }
        sender.sendMessage(comp.clickEvent(ClickEvent.runCommand("/claims perm toggle " + groupName + " explosion"))
                .append(Component.text(i18n.get(I18NKey.PERM_EXPLOSION, senderLocale), NamedTextColor.DARK_GRAY)));
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

        File i18nFile = new File(getDataFolder(), "translations.json");
        if (!i18nFile.exists()) {
            saveResource("translations.json", false);
        }
        try {
            i18n.load(i18nFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

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
                Locale playerLocale = p.locale();
                Location loc = p.getLocation();
                int chunkX = loc.getBlockX() >> 4;
                int chunkY = loc.getBlockZ() >> 4;
                UUID world = p.getWorld().getUID();
                UUID oldClaim = PLAYER_LOCATIONS.get(p.getUniqueId());
                PlayerAttachedScore newClaim = data.getOwner(world, chunkX, chunkY);
                if (newClaim == null) {
                    // now in the wild
                    if (oldClaim != null) {
                        // ... but was not in the wild before!
                        if (TEMPORARY_FLIGHT.remove(p.getUniqueId()) || SESSION_FLIGHT.contains(p.getUniqueId())) {
                            removeFlight(p);
                        }
                        sendActionbarMessage(p, i18n.get(I18NKey.MOVEMENT_TO_WILDERNESS, playerLocale), NamedTextColor.GREEN);
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
                        sendActionbarMessage(p, i18n.get(I18NKey.MOVEMENT_TO_OWN_CLAIM, playerLocale), NamedTextColor.DARK_GREEN);
                    } else {
                        // Entered different claim
                        OfflinePlayer claimOwner = Bukkit.getOfflinePlayer(newClaimOwner);
                        if (!data.getChunkGroupManager().isTrusted(newClaimOwner, p.getUniqueId())) {
                            if (TEMPORARY_FLIGHT.remove(p.getUniqueId()) || SESSION_FLIGHT.remove(p.getUniqueId())) {
                                removeFlight(p);
                            }
                            sendActionbarMessage(p, i18n.get(I18NKey.MOVEMENT_TO_FOREIGN_CLAIM, playerLocale, claimOwner.getName()), NamedTextColor.YELLOW);
                        } else {
                            sendActionbarMessage(p, i18n.get(I18NKey.MOVEMENT_TO_FOREIGN_CLAIM, playerLocale, claimOwner.getName()), NamedTextColor.DARK_BLUE);
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
                        sendActionbarMessage(p, i18n.get(I18NKey.MOVEMENT_TO_OWN_CLAIM, playerLocale), NamedTextColor.DARK_GREEN);
                    } else {
                        // Entered different claim
                        OfflinePlayer claimOwner = Bukkit.getOfflinePlayer(newClaimId);
                        if (!data.getChunkGroupManager().isTrusted(newClaimId, p.getUniqueId())) {
                            sendActionbarMessage(p, i18n.get(I18NKey.MOVEMENT_TO_FOREIGN_CLAIM, playerLocale, claimOwner.getName()), NamedTextColor.YELLOW);
                        } else {
                            sendActionbarMessage(p, i18n.get(I18NKey.MOVEMENT_TO_FOREIGN_CLAIM, playerLocale, claimOwner.getName()), NamedTextColor.DARK_BLUE);
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
                switch (args[0]) {
                case "perm":
                case "perms":
                case "permission":
                case "permissions":
                    if (args.length == 2 && sender instanceof Player p) {
                        List<String> s = new ArrayList<>();
                        Set<ChunkGroup> groups = DataSource.getData().getChunkGroupManager().getOwnedGroups(p.getUniqueId());
                        if (groups == null) {
                            return s;
                        }
                        for (ChunkGroup group : groups) {
                            if (group.name().startsWith(args[1])) {
                                s.add(group.name());
                            }
                        }
                        return s;
                    }
                default:
                    return null;
                }
            }
            List<String> sList = new ArrayList<>(Arrays.asList("togglesb", "help", "map", "trust", "untrust", "perm"));
            if (args.length == 1) {
                sList.removeIf(s -> !s.startsWith(args[0]));
            }
            return sList;
        } else if (command.getName().equals("chunkgroups")) {
            if (args.length == 2 && args[0].equals("assign")) {
                List<String> sList = new ArrayList<>();
                if (sender instanceof Player p) {
                    Set<ChunkGroup> groups = DataSource.getData().getChunkGroupManager().getOwnedGroups(p.getUniqueId());
                    if (groups != null) {
                        for (ChunkGroup group : groups) {
                            if (group.name().startsWith(args[1])) {
                                sList.add(group.name());
                            }
                        }
                    }
                }
                return sList;
            }
            if (args.length == 1) {
                List<String> sList = new ArrayList<>(Arrays.asList("create", "assign", "unassign"));
                sList.removeIf(s -> !s.startsWith(args[0]));
                if (sender instanceof Player p) {
                    Set<ChunkGroup> groups = DataSource.getData().getChunkGroupManager().getOwnedGroups(p.getUniqueId());
                    if (groups != null) {
                        for (ChunkGroup group : groups) {
                            if (group.name().startsWith(args[0])) {
                                sList.add(group.name());
                            }
                        }
                    }
                }
                return sList;
            }
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

            final Locale playerLocale = player.locale();
            final String relationTypeOwner = i18n.get(I18NKey.CLAIMMAP_RELATION_OWNER, playerLocale);
            final String relationTypeTrusted = i18n.get(I18NKey.CLAIMMAP_RELATION_TRUSTED, playerLocale);
            final String relationTypeNeutral = i18n.get(I18NKey.CLAIMMAP_RELATION_NEUTRAL, playerLocale);
            final String relationTypeOther = i18n.get(I18NKey.CLAIMMAP_RELATION_OTHER, playerLocale);
            final String relationTypeThisChunk = i18n.get(I18NKey.CLAIMMAP_RELATION_THIS_CHUNK, playerLocale);
            final String ownerKey = i18n.get(I18NKey.CLAIMMAP_OWNER, playerLocale);
            final String noowner = i18n.get(I18NKey.CLAIMMAP_NO_OWNER, playerLocale);

            for (int yDelta = -5; yDelta < 5; yDelta++) {
                Component comp = Component.empty();
                for (int xDelta = -14; xDelta < 14; xDelta++) {
                    PlayerAttachedScore leader = data.getOwner(world, chunkX + xDelta, chunkY + yDelta);

                    TextComponent.Builder chunk = Component.text();
                    chunk.content(" +");

                    String location = "Chunk " + (chunkX + xDelta) + "/" + (chunkY + yDelta);
                    String ownerName;
                    @NotNull
                    String type;
                    TextColor colorCoding;

                    UUID owner = leader == null ? null : leader.getPlayer();
                    if (owner == null) {
                        type = relationTypeNeutral;
                        ownerName = noowner;
                        colorCoding = NamedTextColor.GRAY;
                        chunk.color(NamedTextColor.GRAY);
                    } else {
                        ownerName = getPlayerName(owner);

                        if (owner.equals(plyr)) {
                            type = relationTypeOwner;
                            colorCoding = NamedTextColor.DARK_GREEN;
                        } else if (data.getChunkGroupManager().isTrusted(owner, plyr)) {
                            colorCoding = NamedTextColor.DARK_BLUE;
                            type = relationTypeTrusted;
                        } else {
                            colorCoding = NamedTextColor.RED;
                            type = relationTypeOther;
                        }
                    }

                    if (xDelta == 0 && yDelta == 0) {
                        colorCoding = NamedTextColor.GOLD;
                        type = relationTypeThisChunk;
                    }

                    chunk.color(colorCoding);
                    chunk.hoverEvent(HoverEvent.showText(Component.text(location, NamedTextColor.YELLOW, TextDecoration.BOLD)
                            .append(Component.newline()).append(Component.text(ownerKey + ownerName, colorCoding))
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
        PlayerAttachedScore leader = presenceData.getOwner(world, chunkX, chunkY);
        Score claimownerPresence = SCOREBOARD_CLAIM_OWNER.get(playerUID);
        Score ownPresence = SCOREBOARD_CLAIM_SELF.get(playerUID);
        Score successorPresence = SCOREBOARD_CLAIM_SUCCESSOR.get(playerUID);
        if (leader == null) {
            claimownerPresence.setScore(0);
            ownPresence.setScore(0);
            successorPresence.setScore(0);
            return;
        }
        PlayerAttachedScore successor = presenceData.getSuccessor(world, chunkX, chunkY);
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

    @NotNull
    @Contract(pure = true)
    LocalisationContainer getI18N() {
        return i18n;
    }
}
