//      Copyright (C) <year>  <name of author>

//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, version 3 of the License, or

//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.

//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <https://www.gnu.org/licenses/>.


package com.example.livemap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

// The main plugin class
public class LiveMaps extends JavaPlugin implements Listener {

    // The scale used for /livemap create default. Used as a fallback and constant.
    // Scale.values()[0] is the max zoom (1:1, 128x128 blocks).
    private final MapView.Scale CREATION_SCALE = MapView.Scale.values()[0];

    // The interval at which we force map updates globally for Item Frames.
    private static final long FORCED_RENDER_INTERVAL_TICKS = 20L;

    private long cacheUpdateIntervalTicks = 5L;
    private boolean showPlayerNames = true;
    private boolean showYLevel = false;
    private boolean showItemFrameNameplate = false;
    private int mapCreationCounter = 0;

    private NamespacedKey locationKey;
    private NamespacedKey gridTargetKey;
    private NamespacedKey confirmKey;
    private NamespacedKey storedNameKey;        // Key to store Item Frame ENTITY name
    private NamespacedKey storedMapItemNameKey; // Key to store MAP ITEM's display name
    private NamespacedKey scaleSelectionKey;    // Key to store the selected scale

    // Map to store the last selected scale per player for session persistence
    private final Map<UUID, MapView.Scale> lastSelectedScale = new HashMap<>();

    private final Set<UUID> playersPendingConfirmation = new HashSet<>();
    private final Set<String> generatedMapCoordinates = new HashSet<>();

    // --- Dynamic Scale Utilities ---

    /**
     * Calculates the scale factor (blocks per pixel) for a given map scale.
     * Scale 0 (max zoom) returns 1. Scale 4 (min zoom) returns 16.
     */
    private int getScaleFactor(MapView.Scale scale) {
        return 1 << scale.ordinal();
    }

    /**
     * Calculates the total block area width/height covered by a map at a given scale.
     * Since the map canvas is 128x128 pixels, the area is 128 * (blocks_per_pixel).
     */
    private int getMapBlockArea(MapView.Scale scale) {
        return getScaleFactor(scale) * 128;
    }

    /**
     * Converts an integer 0-4 into a MapView.Scale object.
     * Defaults to Scale 0 if the integer is invalid.
     */
    private MapView.Scale getMapScaleFromInt(int scaleInt) {
        if (scaleInt >= 0 && scaleInt <= MapView.Scale.values().length - 1) {
            return MapView.Scale.values()[scaleInt];
        }
        return MapView.Scale.values()[0];
    }

    // --- End Dynamic Scale Utilities ---

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigSettings();

        this.locationKey = new NamespacedKey(this, "livemap-locate-target");
        this.gridTargetKey = new NamespacedKey(this, "map-grid-target");
        this.confirmKey = new NamespacedKey(this, "map-generation-confirm");
        this.storedNameKey = new NamespacedKey(this, "livemap-stored-name");
        this.storedMapItemNameKey = new NamespacedKey(this, "livemap-stored-map-name");
        this.scaleSelectionKey = new NamespacedKey(this, "map-scale-select");

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("LiveMaps enabled. Initializing existing live maps...");
        reinitializeMaps();

        applyItemFrameNameplateVisibility(this.showItemFrameNameplate);

        getLogger().info("Starting recurring map update task.");
        startUpdateTask();
    }

    private void loadConfigSettings() {
        this.cacheUpdateIntervalTicks = getConfig().getLong("update-interval-ticks", 5L);
        this.showPlayerNames = getConfig().getBoolean("show-player-names", true);
        this.showYLevel = getConfig().getBoolean("show-y-level", false);
        this.showItemFrameNameplate = getConfig().getBoolean("show-item-frame-nameplate", false);
        reinitializeMaps();
    }

    /**
     * Ensure we only remove OUR renderer before adding a new one.
     */
    private void reinitializeMaps() {
        int count = 0;
        for (short id = 0; id < 32000; id++) {
            MapView view = Bukkit.getMap(id);
            if (view != null) {
                List<MapRenderer> toRemove = new ArrayList<>();
                for (MapRenderer renderer : view.getRenderers()) {
                    if (renderer instanceof PlayerTrackerRenderer) {
                        toRemove.add(renderer);
                    }
                }

                for (MapRenderer renderer : toRemove) {
                    view.removeRenderer(renderer);
                }

                view.addRenderer(new PlayerTrackerRenderer(this));
                count++;
            }
        }
        if (count > 0) {
            getLogger().info("Successfully reinitialized renderers on " + count + " MapViews.");
        }
    }

    @Override
    public void saveDefaultConfig() {
        getConfig().options().copyDefaults(true);
        getConfig().addDefault("update-interval-ticks", 5L);
        getConfig().addDefault("show-player-names", true);
        getConfig().addDefault("show-y-level", false);
        getConfig().addDefault("show-item-frame-nameplate", false);
        super.saveDefaultConfig();
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        playersPendingConfirmation.clear();
        lastSelectedScale.clear(); // Clear session data
        getLogger().info("LiveMaps disabled.");
        generatedMapCoordinates.clear();
        mapCreationCounter = 0;
    }

    private void startUpdateTask() {
        Bukkit.getScheduler().runTaskTimer(this, this::startImmediateMapUpdate, 0L, FORCED_RENDER_INTERVAL_TICKS);
    }

    private void startImmediateMapUpdate() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.getType() == Material.FILLED_MAP && item.getItemMeta() instanceof MapMeta) {
                player.getInventory().setItemInMainHand(item);
            }
        }

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ItemFrame) {
                    ItemFrame frame = (ItemFrame) entity;
                    ItemStack mapItem = frame.getItem();
                    if (mapItem.getType() == Material.FILLED_MAP && mapItem.getItemMeta() instanceof MapMeta) {
                        MapMeta meta = (MapMeta) mapItem.getItemMeta();
                        if (meta.hasMapView()) {
                            MapView view = meta.getMapView();
                            view.setTrackingPosition(true);
                            view.setTrackingPosition(false);

                            if (frame.getRotation() != null) {
                                frame.setRotation(frame.getRotation());
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("livemap")) { return false; }

        String subCommand = (args.length == 0) ? "create" : args[0].toLowerCase(Locale.ROOT);

        switch (subCommand) {
            case "create": return handleCreateMap(sender, label, args);
            case "grid": return handleGridCommand(sender, label, args);
            case "refresh": return handleRefreshMapRender(sender);
            case "use": return handleUseMap(sender, label);
            case "help": return handleHelpCommand(sender, label);
            case "locate": return handleLocateCommand(sender, label);
            case "setupdaterate": return handleSetUpdateRate(sender, args, label);
            case "togglenames": return handleToggleNamesCommand(sender, label);
            case "toggley": return handleToggleYCommand(sender, label);
            case "togglenameplate": return handleToggleNameplateCommand(sender, label);
            case "reload": return handleReloadCommand(sender, label);
            default: sender.sendMessage(ChatColor.RED + "Unknown subcommand: /" + label + " " + args[0]); return handleHelpCommand(sender, label);
        }
    }

    // --- CREATION COMMAND HANDLERS ---

    private boolean handleCreateMap(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Players only."); return true; }
        if (!sender.hasPermission("livemap.create")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }

        Player player = (Player) sender;
        Location playerLoc = player.getLocation();
        MapView.Scale targetScale = CREATION_SCALE; // Default to Scale 0 (max zoom)

        // 1. Parse optional scale argument (kept for /livemap create)
        if (args.length >= 2) {
            try {
                int scaleInt = Integer.parseInt(args[1]);
                if (scaleInt >= 0 && scaleInt <= 4) {
                    targetScale = getMapScaleFromInt(scaleInt);
                } else {
                    sender.sendMessage(ChatColor.RED + "Invalid scale. Must be between 0 (max zoom) and 4 (min zoom).");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Usage: /" + label + " create [scale 0-4]");
                return true;
            }
        }

        // 2. Calculate center based on Scale 0 grid snapping (fixed snapping for /create)
        int mapBlockArea = getMapBlockArea(CREATION_SCALE);
        int centerOffset = mapBlockArea / 2;

        int mapOriginX = (int) Math.floor((double) playerLoc.getBlockX() / mapBlockArea) * mapBlockArea;
        int mapOriginZ = (int) Math.floor((double) playerLoc.getBlockZ() / mapBlockArea) * mapBlockArea;
        int snappedCenterX = mapOriginX + centerOffset;
        int snappedCenterZ = mapOriginZ + centerOffset;

        // 3. Create the map with the requested scale
        MapView createdMap = createAndConfigureMap(player.getWorld(), snappedCenterX, snappedCenterZ, player, targetScale);
        trackMapCoordinates(createdMap);

        player.sendMessage(ChatColor.GREEN + "Map generated at scale " + targetScale.ordinal() + ".");
        player.sendMessage(ChatColor.GRAY + "(Center: X=" + snappedCenterX + ", Z=" + snappedCenterZ + ")");
        return true;
    }

    private boolean handleGridCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Players only."); return true; }
        if (!sender.hasPermission("livemap.create")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }

        Player player = (Player) sender;

        // 1. If confirmation is pending, open the confirmation GUI directly
        if (playersPendingConfirmation.contains(player.getUniqueId())) {
             openConfirmationGui(player);
             return true;
        }

        // 2. Check for a previously selected scale (Session Persistence)
        MapView.Scale targetScale = lastSelectedScale.get(player.getUniqueId());

        if (targetScale != null) {
            // Scale found, jump straight to the grid view
            openDirectionalGridGui(player, player.getLocation(), targetScale);
        } else {
            // No scale found, open the Scale Selection GUI
            openScaleSelectionGui(player);
        }

        return true;
    }

    /**
     * Core map creation logic, now accepts the desired scale.
     */
    private MapView createAndConfigureMap(World world, int centerX, int centerZ, Player player, MapView.Scale scale) {
        MapView newMapView = Bukkit.createMap(world);

        List<MapRenderer> toRemove = new ArrayList<>();
        for (MapRenderer renderer : newMapView.getRenderers()) {
            if (renderer instanceof PlayerTrackerRenderer) {
                toRemove.add(renderer);
            }
        }
        for (MapRenderer renderer : toRemove) {
            newMapView.removeRenderer(renderer);
        }

        newMapView.setCenterX(centerX);
        newMapView.setCenterZ(centerZ);
        newMapView.setTrackingPosition(false);
        newMapView.setLocked(false);
        // USE THE PROVIDED SCALE
        newMapView.setScale(scale);
        newMapView.addRenderer(new PlayerTrackerRenderer(this));

        if (player != null) {
            this.mapCreationCounter++;
            String mapName = ChatColor.AQUA + "Live Map #" + this.mapCreationCounter + " (Scale " + scale.ordinal() + ")";

            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) mapItem.getItemMeta();
            meta.setMapView(newMapView);
            meta.setDisplayName(mapName);
            mapItem.setItemMeta(meta);

            player.getInventory().addItem(mapItem);
            player.sendMessage(ChatColor.GREEN + "New map generated: " + mapName);
        }
        return newMapView;
    }

    // --- GUI METHODS ---

    private void openScaleSelectionGui(Player player) {
        Inventory gui = Bukkit.createInventory(player, 9, ChatColor.DARK_GREEN + "Select Map Scale (Zoom Level)");

        for (int i = 0; i <= 4; i++) {
            MapView.Scale scale = getMapScaleFromInt(i);

            int blockArea = getMapBlockArea(scale);
            int scaleFactor = getScaleFactor(scale);

            Material iconMaterial = Material.PAPER;
            ChatColor color = ChatColor.AQUA;
            if (i == 0) {
                iconMaterial = Material.FILLED_MAP;
                color = ChatColor.GREEN;
            } else if (i == 4) {
                iconMaterial = Material.MAP;
                color = ChatColor.RED;
            }

            ItemStack item = new ItemStack(iconMaterial);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            meta.setDisplayName(color + "Scale " + i + " (1:" + scaleFactor + " Zoom)");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Covers " + blockArea + "x" + blockArea + " blocks.");
            if (i == 0) {
                 lore.add(ChatColor.YELLOW + "Max Zoom (1:1). Perfect for map walls.");
            } else if (i == 4) {
                 lore.add(ChatColor.YELLOW + "Min Zoom (1:16). Wide area view.");
            }
            meta.setLore(lore);

            meta.getPersistentDataContainer().set(scaleSelectionKey, PersistentDataType.INTEGER, i);

            item.setItemMeta(meta);
            gui.setItem(i + 2, item);
        }

        player.openInventory(gui);
    }

    private void openDirectionalGridGui(Player player, Location centerLocation, MapView.Scale targetScale) {
        Inventory gui = Bukkit.createInventory(player, 54, ChatColor.DARK_GREEN + "Live Map Grid (5x5)");

        // Grid snapping logic now uses the TARGET SCALE for block area to match the chosen map size
        int mapBlockArea = getMapBlockArea(targetScale);
        int centerOffset = mapBlockArea / 2;

        int currentGridX = (int) Math.floor((double) centerLocation.getBlockX() / mapBlockArea) * mapBlockArea;
        int currentGridZ = (int) Math.floor((double) centerLocation.getBlockZ() / mapBlockArea) * mapBlockArea;
        World world = centerLocation.getWorld();

        for (int zOffset = -2; zOffset <= 2; zOffset++) {
            for (int xOffset = -2; xOffset <= 2; xOffset++) {
                int gridY = zOffset + 2;
                int gridX = xOffset + 2;
                int guiIndex = ((gridY + 1) * 9) + (gridX + 2);

                int targetGridX = currentGridX + xOffset * mapBlockArea;
                int targetGridZ = currentGridZ + zOffset * mapBlockArea;
                int targetCenterX = targetGridX + centerOffset;
                int targetCenterZ = targetGridZ + centerOffset;

                boolean isCurrentCenter = (xOffset == 0 && zOffset == 0);
                boolean alreadyGenerated = isMapGenerated(world, targetCenterX, targetCenterZ);

                Material iconMaterial;
                ChatColor color;

                if (isCurrentCenter) {
                    iconMaterial = Material.LIME_STAINED_GLASS_PANE;
                    color = ChatColor.GREEN;
                } else if (alreadyGenerated) {
                    iconMaterial = Material.GREEN_STAINED_GLASS_PANE;
                    color = ChatColor.GREEN;
                } else {
                    iconMaterial = Material.ORANGE_STAINED_GLASS_PANE;
                    color = ChatColor.YELLOW;
                }

                ItemStack item = new ItemStack(iconMaterial);
                ItemMeta meta = item.getItemMeta();
                if (meta == null) continue;

                meta.setDisplayName(color + "Map Chunk (" + xOffset + ", " + zOffset + ")");
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Center X: " + targetCenterX);
                lore.add(ChatColor.GRAY + "Center Z: " + targetCenterZ);
                // Updated Lore to reflect the actual area covered by the grid square
                lore.add(ChatColor.DARK_AQUA + "Area: " + mapBlockArea + "x" + mapBlockArea + " blocks");
                lore.add(ChatColor.DARK_AQUA + "Map Scale: " + targetScale.ordinal());
                meta.setLore(lore);

                // Store center coordinates and the requested scale in PDC
                String gridTargetString = world.getName() + "," + targetCenterX + "," + targetCenterZ + "," + targetScale.ordinal();
                meta.getPersistentDataContainer().set(gridTargetKey, PersistentDataType.STRING, gridTargetString);

                item.setItemMeta(meta);
                gui.setItem(guiIndex, item);
            }
        }

        ItemStack closeItem = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = closeItem.getItemMeta();
        closeMeta.setDisplayName(ChatColor.RED + "Close Session");
        closeMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Ends current scale session."));
        closeItem.setItemMeta(closeMeta);
        gui.setItem(49, closeItem);

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) gui.setItem(i, filler);
        }

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String title = event.getView().getTitle();
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null) return;

        boolean isGridGui = title.startsWith(ChatColor.DARK_GREEN + "Live Map Grid (5x5)");
        boolean isConfirmGui = title.startsWith(ChatColor.AQUA + "Confirm Map Generation");
        boolean isScaleGui = title.startsWith(ChatColor.DARK_GREEN + "Select Map Scale (Zoom Level)");


        if (isGridGui || isConfirmGui || isScaleGui) {
            event.setCancelled(true);

            if (isConfirmGui) {
                if (meta.getPersistentDataContainer().has(confirmKey, PersistentDataType.BYTE)) {
                    playersPendingConfirmation.remove(player.getUniqueId());
                    player.closeInventory();

                    MapView.Scale targetScale = lastSelectedScale.get(player.getUniqueId());

                    if (targetScale != null) {
                        // NEW LOGIC: Immediately re-open the grid GUI
                        openDirectionalGridGui(player, player.getLocation(), targetScale);
                        player.sendMessage(ChatColor.GREEN + "Confirmed. Continuing map generation session.");
                    } else {
                        // Fallback if session data was somehow lost
                        player.sendMessage(ChatColor.GREEN + "Confirmed. Map session closed (scale data missing). Run /livemap grid to start a new session.");
                    }
                }
                return;
            }

            // Handle Scale Selection Click
            if (isScaleGui) {
                if (meta.getPersistentDataContainer().has(scaleSelectionKey, PersistentDataType.INTEGER)) {
                    int scaleInt = meta.getPersistentDataContainer().get(scaleSelectionKey, PersistentDataType.INTEGER);
                    MapView.Scale targetScale = getMapScaleFromInt(scaleInt);

                    // SAVE THE SELECTED SCALE FOR THE SESSION
                    lastSelectedScale.put(player.getUniqueId(), targetScale);

                    player.closeInventory();
                    // Proceed to the grid view with the selected scale
                    openDirectionalGridGui(player, player.getLocation(), targetScale);
                }
                return;
            }

            if (isGridGui) {
                // If closing the grid, END THE SESSION by clearing the scale
                if (clickedItem.getType() == Material.BARRIER) {
                    player.closeInventory();
                    lastSelectedScale.remove(player.getUniqueId()); // <--- END SESSION
                    player.sendMessage(ChatColor.YELLOW + "Map grid session finished. Scale setting cleared.");
                    return;
                }

                if (meta.getPersistentDataContainer().has(gridTargetKey, PersistentDataType.STRING)) {
                    String gridTargetString = meta.getPersistentDataContainer().get(gridTargetKey, PersistentDataType.STRING);
                    try {
                        String[] parts = gridTargetString.split(",");

                        // Expect 4 parts: World, CenterX, CenterZ, Scale
                        if (parts.length < 4) return;

                        World world = Bukkit.getWorld(parts[0]);
                        int centerX = Integer.parseInt(parts[1]);
                        int centerZ = Integer.parseInt(parts[2]);
                        MapView.Scale mapScale = getMapScaleFromInt(Integer.parseInt(parts[3]));

                        if (world == null) return;

                        if (!isMapGenerated(world, centerX, centerZ)) {
                            // Pass the requested scale to the creation method
                            MapView newMap = createAndConfigureMap(world, centerX, centerZ, player, mapScale);
                            trackMapCoordinates(newMap);
                        }

                        int y = world.getHighestBlockYAt(centerX, centerZ) + 1;
                        Location targetLoc = new Location(world, centerX + 0.5, y, centerZ + 0.5);

                        player.closeInventory();
                        player.teleport(targetLoc);

                        playersPendingConfirmation.add(player.getUniqueId());

                        // Message prompting user to render map
                        player.sendMessage(ChatColor.AQUA + "Teleported. Map created at scale " + mapScale.ordinal() + ". " + ChatColor.YELLOW + "Move around the area to fully render the map " + ChatColor.AQUA + "before using " + ChatColor.YELLOW + "/livemap grid" + ChatColor.AQUA + " to confirm.");

                    } catch (Exception e) {
                        player.closeInventory();
                    }
                }
            }
        }
    }

    // --- OTHER METHODS (UNMODIFIED) ---

    private void applyItemFrameNameplateVisibility(boolean visible) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ItemFrame) {
                    ItemFrame frame = (ItemFrame) entity;
                    ItemStack mapItem = frame.getItem();

                    if (mapItem.getType() == Material.FILLED_MAP || mapItem.getType() == Material.MAP) {
                        ItemStack originalMap = mapItem.clone();
                        MapMeta mapMeta = (MapMeta) originalMap.getItemMeta();

                        if (visible) {
                            if (frame.getPersistentDataContainer().has(storedNameKey, PersistentDataType.STRING)) {
                                String storedName = frame.getPersistentDataContainer().get(storedNameKey, PersistentDataType.STRING);
                                frame.setCustomName(storedName);
                                frame.getPersistentDataContainer().remove(storedNameKey);
                            }
                            if (frame.getPersistentDataContainer().has(storedMapItemNameKey, PersistentDataType.STRING)) {
                                String storedMapName = frame.getPersistentDataContainer().get(storedMapItemNameKey, PersistentDataType.STRING);
                                mapMeta.setDisplayName(storedMapName);
                                originalMap.setItemMeta(mapMeta);
                                frame.getPersistentDataContainer().remove(storedMapItemNameKey);
                            }

                            boolean hasAnyCustomName =
                                (frame.getCustomName() != null && !frame.getCustomName().isEmpty()) ||
                                (mapMeta.hasDisplayName());

                            frame.setCustomNameVisible(hasAnyCustomName);

                        } else {
                            if (frame.getCustomName() != null && !frame.getCustomName().isEmpty()) {
                                frame.getPersistentDataContainer().set(storedNameKey, PersistentDataType.STRING, frame.getCustomName());
                                frame.setCustomName(null);
                            }

                            if (mapMeta.hasDisplayName()) {
                                frame.getPersistentDataContainer().set(storedMapItemNameKey, PersistentDataType.STRING, mapMeta.getDisplayName());
                                mapMeta.setDisplayName(null);
                                originalMap.setItemMeta(mapMeta);
                            }

                            frame.setCustomNameVisible(false);
                        }

                        frame.setItem(new ItemStack(Material.AIR), false);

                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            frame.setItem(originalMap, false);

                            if (frame.getRotation() != null) {
                                frame.setRotation(frame.getRotation());
                            }
                        }, 1L);
                    }
                }
            }
        }
    }

    private boolean handleToggleNameplateCommand(CommandSender sender, String label) {
        if (!sender.hasPermission("livemap.config")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command.");
            return true;
        }

        this.showItemFrameNameplate = !this.showItemFrameNameplate;
        getConfig().set("show-item-frame-nameplate", this.showItemFrameNameplate);
        saveConfig();

        applyItemFrameNameplateVisibility(this.showItemFrameNameplate);

        String status = this.showItemFrameNameplate ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF";
        sender.sendMessage(ChatColor.AQUA + "Item Frame Nameplate visibility toggled " + status + ChatColor.AQUA + ". Existing Item Frames updated.");

        return true;
    }

    private boolean handleUseMap(CommandSender sender, String label) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }
        if (!sender.hasPermission("livemap.use")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command.");
            return true;
        }

        Player player = (Player) sender;
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item.getType() != Material.FILLED_MAP || !(item.getItemMeta() instanceof MapMeta)) {
            player.sendMessage(ChatColor.RED + "You must be holding a filled map to use this command.");
            return true;
        }

        MapMeta meta = (MapMeta) item.getItemMeta();
        if (!meta.hasMapView()) {
            player.sendMessage(ChatColor.RED + "The map is empty or corrupted.");
            return true;
        }

        MapView view = meta.getMapView();

        List<MapRenderer> toRemove = new ArrayList<>(view.getRenderers());
        for (MapRenderer renderer : toRemove) {
            if (renderer instanceof PlayerTrackerRenderer) {
                view.removeRenderer(renderer);
            }
        }

        view.addRenderer(new PlayerTrackerRenderer(this));

        Bukkit.getScheduler().runTask(this, () -> {
            player.getInventory().setItemInMainHand(item);
        });

        player.sendMessage(ChatColor.GREEN + "Live map renderer forced onto map ID " + view.getId() + ".");
        player.sendMessage(ChatColor.YELLOW + "Map will refresh in your hand shortly. Use /livemap refresh if needed.");

        return true;
    }

    private boolean handleToggleNamesCommand(CommandSender sender, String label) {
        if (!sender.hasPermission("livemap.config")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command.");
            return true;
        }

        this.showPlayerNames = !this.showPlayerNames;

        getConfig().set("show-player-names", this.showPlayerNames);
        saveConfig();

        Bukkit.getScheduler().runTask(this, this::startImmediateMapUpdate);

        String status = this.showPlayerNames ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF";
        sender.sendMessage(ChatColor.AQUA + "Player name visibility toggled " + status + ChatColor.AQUA + " on all live maps.");

        return true;
    }

    private boolean handleToggleYCommand(CommandSender sender, String label) {
        if (!sender.hasPermission("livemap.config")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command.");
            return true;
        }

        this.showYLevel = !this.showYLevel;
        getConfig().set("show-y-level", this.showYLevel);
        saveConfig();

        Bukkit.getScheduler().runTask(this, this::startImmediateMapUpdate);

        String status = this.showYLevel ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF";
        sender.sendMessage(ChatColor.AQUA + "Vertical tracking (Y-Level) toggled " + status + ChatColor.AQUA + " on all live maps.");

        return true;
    }

    private boolean handleRefreshMapRender(CommandSender sender) {
        if (!sender.hasPermission("livemap.config")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to refresh map renders.");
            return true;
        }

        reinitializeMaps();
        Bukkit.getScheduler().runTask(this, this::startImmediateMapUpdate);

        sender.sendMessage(ChatColor.GREEN + "Refreshed custom renderers on all maps.");
        return true;
    }

    private boolean handleReloadCommand(CommandSender sender, String label) {
        if (!sender.hasPermission("livemap.config")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command.");
            return true;
        }
        reloadConfig();
        loadConfigSettings();
        generatedMapCoordinates.clear();
        playersPendingConfirmation.clear();
        lastSelectedScale.clear(); // Clear session data on reload
        this.mapCreationCounter = 0;

        applyItemFrameNameplateVisibility(this.showItemFrameNameplate);

        sender.sendMessage(ChatColor.GREEN + "Configuration reloaded. Map performance should now be restored.");
        return true;
    }

    private boolean handleLocateCommand(CommandSender sender, String label) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "This command can only be run by a player."); return true; }
        if (!sender.hasPermission("livemap.config")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        sender.sendMessage(ChatColor.YELLOW + "Not implemented.");
        return true;
    }

    private boolean handleSetUpdateRate(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("livemap.config")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " setupdaterate <ticks>");
            return true;
        }
        try {
            long newRate = Long.parseLong(args[1]);
            if (newRate < 1) {
                sender.sendMessage(ChatColor.RED + "Rate must be >= 1.");
                return true;
            }
            this.cacheUpdateIntervalTicks = newRate;
            getConfig().set("update-interval-ticks", newRate);
            saveConfig();
            reinitializeMaps();
            sender.sendMessage(ChatColor.GREEN + "Update rate set to " + newRate + " ticks.");
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid number.");
        }
        return true;
    }

    private boolean handleHelpCommand(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.DARK_AQUA + "--- Live Map Help ---");
        sender.sendMessage(ChatColor.YELLOW + "/livemap create [scale 0-4] - Create a map at your location. Default is scale 0 (max zoom).");
        sender.sendMessage(ChatColor.YELLOW + "/livemap grid - Open the GUI to select map scale and position (session based).");
        sender.sendMessage(ChatColor.YELLOW + "/livemap use - Apply renderer to held map");
        sender.sendMessage(ChatColor.YELLOW + "/livemap togglenames - Toggle player name tags on maps");
        sender.sendMessage(ChatColor.YELLOW + "/livemap toggley - Toggle vertical (Y-Level) tracking");
        sender.sendMessage(ChatColor.YELLOW + "/livemap togglenameplate - Toggle the item frame nameplate visibility");
        sender.sendMessage(ChatColor.YELLOW + "/livemap refresh");
        sender.sendMessage(ChatColor.YELLOW + "/livemap reload");
        return true;
    }

    private void openConfirmationGui(Player player) {
        Inventory gui = Bukkit.createInventory(player, 9, ChatColor.AQUA + "Confirm Map Generation");
        ItemStack confirmButton = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = confirmButton.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Map Chunks Generated - Continue");
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to confirm area is loaded."));
            meta.getPersistentDataContainer().set(confirmKey, PersistentDataType.BYTE, (byte) 1);
            confirmButton.setItemMeta(meta);
        }
        gui.setItem(4, confirmButton);
        player.openInventory(gui);
    }

    private void trackMapCoordinates(MapView view) {
        // Track maps using their center coordinates
        String coordinateString = view.getWorld().getName() + "," + view.getCenterX() + "," + view.getCenterZ();
        generatedMapCoordinates.add(coordinateString);
    }

    private boolean isMapGenerated(World world, int centerX, int centerZ) {
        // Check if a map has been generated for these center coordinates
        String coordinateString = world.getName() + "," + centerX + "," + centerZ;
        return generatedMapCoordinates.contains(coordinateString);
    }

    // --- Custom MapRenderer Class (No changes needed) ---
    private class PlayerTrackerRenderer extends MapRenderer {

        private final LiveMaps plugin;

        private final Map<Integer, Byte> modifiedPixels = new HashMap<>();

        private final byte[] PLAYER_COLOR_IDS = new byte[] {
            34, 58, 86, 98, 114, 126, 78, 90, 102, 110, 66, 74
        };
        private static final byte DIRECTION_LINE_COLOR_ID = 119;

        public PlayerTrackerRenderer(LiveMaps plugin) {
            super(false);
            this.plugin = plugin;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player holder) {
            restoreMapBackground(canvas);

            MapCursorCollection cursors = canvas.getCursors();
            for (int i = cursors.size() - 1; i >= 0; i--) {
                 cursors.removeCursor(cursors.getCursor(i));
            }

            MapView.Scale currentScale = map.getScale();
            int scaleFactor = plugin.getScaleFactor(currentScale);

            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!target.getWorld().equals(map.getWorld())) continue;

                if (target.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                    continue;
                }

                Location loc = target.getLocation();
                int centerX = map.getCenterX();
                int centerZ = map.getCenterZ();

                int worldDiffX = loc.getBlockX() - centerX;
                int worldDiffZ = loc.getBlockZ() - centerZ;

                int mapX = (worldDiffX / scaleFactor) + 64;
                int mapZ = (worldDiffZ / scaleFactor) + 64;

                if (mapX >= 0 && mapX < 128 && mapZ >= 0 && mapZ < 128) {

                    int colorIndex = Math.abs(target.getUniqueId().hashCode()) % PLAYER_COLOR_IDS.length;
                    byte playerColor = PLAYER_COLOR_IDS[colorIndex];

                    float yaw = loc.getYaw();
                    drawLargeIcon(canvas, mapX, mapZ, yaw, playerColor, DIRECTION_LINE_COLOR_ID);

                    if (plugin.showPlayerNames || plugin.showYLevel) {
                        if (plugin.showYLevel) {
                            int yLevel = loc.getBlockY();
                            String yText = "" + yLevel;
                            byte textColorID;
                            if (yLevel > 100) {
                                textColorID = (byte) 54;
                            } else if (yLevel >= 54) {
                                textColorID = (byte) 86;
                            } else {
                                textColorID = (byte) 114;
                            }

                            int width = MinecraftFont.Font.getWidth(yText);
                            int height = MinecraftFont.Font.getHeight();
                            int yTextX = mapX - (width / 2);
                            int yTextY = mapZ - 8 - height;

                            drawSmartRectangle(canvas, yTextX - 1, yTextY - 1, width + 2, height + 2, (byte) 119);
                            drawSmartText(canvas, yTextX, yTextY, yText, textColorID);
                        }

                        if (plugin.showPlayerNames) {
                            String name = ChatColor.stripColor(target.getName());
                            if (name != null && !name.isEmpty() && MinecraftFont.Font.isValid(name)) {
                                int width = MinecraftFont.Font.getWidth(name);
                                int height = MinecraftFont.Font.getHeight();

                                int textX = mapX - (width / 2);
                                int textY = mapZ + 8;

                                drawSmartRectangle(canvas, textX - 1, textY - 1, width + 2, height + 2, (byte) 119);
                                drawSmartText(canvas, textX, textY, name, (byte) 34);
                            }
                        }
                    }
                }
            }
        }

        private void restoreMapBackground(MapCanvas canvas) {
            if (modifiedPixels.isEmpty()) return;

            for (Map.Entry<Integer, Byte> entry : modifiedPixels.entrySet()) {
                int index = entry.getKey();
                int x = index % 128;
                int y = index / 128;

                if (x >= 0 && x < 128 && y >= 0 && y < 128) {
                    canvas.setPixel(x, y, entry.getValue());
                }
            }
            modifiedPixels.clear();
        }

        private void setSmartPixel(MapCanvas canvas, int x, int y, byte colorId) {
            if (x >= 0 && x < 128 && y >= 0 && y < 128) {
                int index = y * 128 + x;
                if (!modifiedPixels.containsKey(index)) {
                    modifiedPixels.put(index, canvas.getPixel(x, y));
                }
                canvas.setPixel(x, y, colorId);
            }
        }

        private void drawSmartRectangle(MapCanvas canvas, int x, int y, int width, int height, byte colorId) {
            for (int py = y; py < y + height; py++) {
                for (int px = x; px < x + width; px++) {
                    setSmartPixel(canvas, px, py, colorId);
                }
            }
        }

        private void drawSmartText(MapCanvas canvas, int x, int y, String text, byte colorId) {
            int currentX = x;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (!MinecraftFont.Font.isValid(String.valueOf(ch))) continue;

                MapFont.CharacterSprite sprite = MinecraftFont.Font.getChar(ch);
                for (int row = 0; row < sprite.getHeight(); row++) {
                    for (int col = 0; col < sprite.getWidth(); col++) {
                        if (sprite.get(row, col)) {
                            setSmartPixel(canvas, currentX + col, y + row, colorId);
                        }
                    }
                }
                currentX += sprite.getWidth() + 1;
            }
        }

        private void drawLargeIcon(MapCanvas canvas, int centerX, int centerY, float yaw, byte fillColor, byte lineFillColor) {
            int halfSize = 4;
            for (int x = -halfSize; x <= halfSize; x++) {
                for (int y = -halfSize; y <= halfSize; y++) {
                    if (Math.abs(x) + Math.abs(y) <= halfSize) {
                        setSmartPixel(canvas, centerX + x, centerY + y, fillColor);
                    }
                }
            }

            float normalizedYaw = (yaw % 360 + 360) % 360;
            double mapAngle = (normalizedYaw + 180) % 360;
            double angleRad = Math.toRadians(mapAngle);

            double cos = Math.cos(angleRad);
            double sin = Math.sin(angleRad);

            double px = 0.0;
            double py = -4.0;

            int tipX = (int) Math.round(centerX + (px * cos - py * sin));
            int tipY = (int) Math.round(centerY + (px * sin + py * cos));

            drawLine(canvas, centerX, centerY, tipX, tipY, lineFillColor);

            drawSmartRectangle(canvas, tipX - 1, tipY - 1, 2, 2, lineFillColor);
        }

        private void drawLine(MapCanvas canvas, int x0, int y0, int x1, int y1, byte colorId) {
            int dx = Math.abs(x1 - x0);
            int dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1;
            int sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;

            while (true) {
                setSmartPixel(canvas, x0, y0, colorId);
                if (x0 == x1 && y0 == y1) break;
                int e2 = 2 * err;
                if (e2 > -dy) {
                    err -= dy;
                    x0 += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    y0 += sy;
                }
            }
        }
    }
}
