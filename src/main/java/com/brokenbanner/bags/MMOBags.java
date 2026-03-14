package com.brokenbanner.bags;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MMOBags extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey bagTypeKey, bagTierKey, bagContentKey, bagUuidKey, bagToggleKey, bagLoreKey;
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");

    // NEW: Cooldown map to prevent "Vanish/Loop" bug
    private final Map<UUID, Long> vacuumCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        bagTypeKey = new NamespacedKey(this, "bag_type");
        bagTierKey = new NamespacedKey(this, "bag_tier");
        bagContentKey = new NamespacedKey(this, "bag_contents");
        bagUuidKey = new NamespacedKey(this, "bag_uuid");
        bagToggleKey = new NamespacedKey(this, "bag_pickup_toggle");
        bagLoreKey = new NamespacedKey(this, "bag_custom_lore");

        saveDefaultConfig();
        createMessagesConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("bagcreate").setExecutor(this);
        getCommand("baggive").setExecutor(this);
        getCommand("bagreload").setExecutor(this);
        getCommand("bagadditem").setExecutor(this);
        getCommand("bageditlore").setExecutor(this);

        // Optimized Vacuum Task
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                // If player is in cooldown (just dropped something), skip them
                if (vacuumCooldown.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) continue;

                for (Entity e : p.getNearbyEntities(1.5, 1.5, 1.5)) {
                    if (e instanceof Item dropped && !dropped.isDead()) {
                        handleCustomPickup(p, dropped);
                    }
                }
            }
        }, 20L, 10L);
    }

    private void createMessagesConfig() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private String color(String msg) {
        if (msg == null) return "";
        Matcher matcher = hexPattern.matcher(msg);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
        }
        return ChatColor.translateAlternateColorCodes('&', matcher.appendTail(buffer).toString());
    }

    private String getMsg(String path) {
        String lang = getConfig().getString("language", "en");
        String message = messagesConfig.getString(lang + "." + path, messagesConfig.getString("en." + path));
        return color(message);
    }

    private static class BagHolder implements InventoryHolder {
        private final String uuid, type;
        public BagHolder(String uuid, String type) { this.uuid = uuid; this.type = type; }
        public String getUuid() { return uuid; }
        public String getType() { return type; }
        @Override public Inventory getInventory() { return null; }
    }

    public void refreshLore(ItemStack bag) {
        if (!isBag(bag)) return;
        ItemMeta meta = bag.getItemMeta();
        String type = meta.getPersistentDataContainer().get(bagTypeKey, PersistentDataType.STRING);
        int tier = meta.getPersistentDataContainer().getOrDefault(bagTierKey, PersistentDataType.INTEGER, 1);
        int max = tier * 9;

        String path = "bags." + type;
        if (getConfig().contains(path + ".display-name")) {
            meta.setDisplayName(color(getConfig().getString(path + ".display-name").replace("%tier%", String.valueOf(tier))));
        }
        if (getConfig().contains(path + ".custom-model-data")) {
            meta.setCustomModelData(getConfig().getInt(path + ".custom-model-data"));
        }

        byte toggleByte = meta.getPersistentDataContainer().getOrDefault(bagToggleKey, PersistentDataType.BYTE, (byte) 1);
        boolean pickupEnabled = toggleByte == 1;
        String customLore = meta.getPersistentDataContainer().getOrDefault(bagLoreKey, PersistentDataType.STRING, "");

        String encoded = meta.getPersistentDataContainer().get(bagContentKey, PersistentDataType.STRING);
        ItemStack[] contents = (encoded == null || encoded.isEmpty()) ? new ItemStack[0] : deserialize(encoded);
        long used = Arrays.stream(contents).filter(i -> i != null && i.getType() != Material.AIR).count();

        double percent = (double) used / max;
        int filledBars = (int) (percent * 10);
        String barColor = percent < 0.5 ? "§a" : (percent < 0.9 ? "§e" : "§c");

        StringBuilder bar = new StringBuilder(barColor);
        for (int i = 0; i < 10; i++) {
            if (i == filledBars) bar.append("§8");
            bar.append("|");
        }

        List<String> lore = new ArrayList<>();
        if (!customLore.isEmpty()) {
            for (String line : customLore.split("\\|")) lore.add("§7§o" + line.replace("_", " "));
            lore.add("");
        }

        lore.add(getMsg("bag-lore-capacity").replace("%size%", String.valueOf(max)));
        lore.add("§7" + bar.toString() + " §f" + (int)(percent * 100) + "%");

        if (getConfig().getBoolean(path + ".allow-pickup-toggling", true)) {
            lore.add("");
            lore.add("§7Pickup: " + (pickupEnabled ? "§aENABLED" : "§cDISABLED"));
            lore.add("§8Shift+Right-Click to toggle");
        }

        lore.add("");
        lore.add(getMsg("bag-lore-mmo"));

        meta.setLore(lore);
        bag.setItemMeta(meta);
    }

    private void ensureConfigEntry(String type) {
        String path = "bags." + type.toUpperCase();
        if (!getConfig().contains(path)) {
            getConfig().set(path + ".display-name", "&f" + type + " Bag [T%tier%]");
            getConfig().set(path + ".items", new ArrayList<String>());
            getConfig().set(path + ".pickup-sound", "ENTITY_ITEM_PICKUP");
            getConfig().set(path + ".pickup-pitch", 1.0);
            getConfig().set(path + ".allow-pickup-toggling", true);
            saveConfig();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        Player player = (Player) event.getWhoClicked();

        if (event.getInventory().getHolder() instanceof BagHolder holder) {
            ItemStack cursor = event.getCursor();
            if (isBag(cursor)) {
                String cursorUuid = cursor.getItemMeta().getPersistentDataContainer().get(bagUuidKey, PersistentDataType.STRING);
                if (cursorUuid.equals(holder.getUuid())) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                if (isBag(item)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (isBag(item)) {
            String type = item.getItemMeta().getPersistentDataContainer().get(bagTypeKey, PersistentDataType.STRING);
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                event.setCancelled(true);
                if (getConfig().getBoolean("bags." + type + ".allow-pickup-toggling", true)) {
                    togglePickup(player, item);
                }
                return;
            } else if (event.getClick() == ClickType.RIGHT) {
                event.setCancelled(true);
                openBag(player, item);
                return;
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!isBag(item)) return;
        String type = item.getItemMeta().getPersistentDataContainer().get(bagTypeKey, PersistentDataType.STRING);

        if (event.getPlayer().isSneaking() && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            if (getConfig().getBoolean("bags." + type + ".allow-pickup-toggling", true)) togglePickup(event.getPlayer(), item);
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            openBag(event.getPlayer(), item);
        }
    }

    public void openBag(Player player, ItemStack bag) {
        ItemMeta meta = bag.getItemMeta();
        String type = meta.getPersistentDataContainer().get(bagTypeKey, PersistentDataType.STRING);
        int tier = meta.getPersistentDataContainer().getOrDefault(bagTierKey, PersistentDataType.INTEGER, 1);
        String uuid = meta.getPersistentDataContainer().get(bagUuidKey, PersistentDataType.STRING);

        Inventory inv = Bukkit.createInventory(new BagHolder(uuid, type), Math.min(tier * 9, 54), getMsg("inventory-title"));
        String encoded = meta.getPersistentDataContainer().get(bagContentKey, PersistentDataType.STRING);
        if (encoded != null && !encoded.isEmpty()) inv.setContents(deserialize(encoded));

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.5f, 0.8f);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BagHolder holder)) return;
        String bagUuid = holder.getUuid();
        Player player = (Player) event.getPlayer();

        // Trigger cooldown so items dropped while closing don't vanish
        vacuumCooldown.put(player.getUniqueId(), System.currentTimeMillis() + 1000L);

        for (ItemStack item : player.getInventory().getContents()) {
            if (isBag(item)) {
                ItemMeta meta = item.getItemMeta();
                if (bagUuid.equals(meta.getPersistentDataContainer().get(bagUuidKey, PersistentDataType.STRING))) {
                    meta.getPersistentDataContainer().set(bagContentKey, PersistentDataType.STRING, serialize(event.getInventory().getContents()));
                    item.setItemMeta(meta);
                    refreshLore(item);
                    return;
                }
            }
        }
    }

    // NEW: Handle manual dropping too
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        vacuumCooldown.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 1500L);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (vacuumCooldown.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) return;

        handleCustomPickup(player, event.getItem());
        if (event.getItem().isDead()) event.setCancelled(true);
    }

    private void handleCustomPickup(Player player, Item droppedItem) {
        ItemStack pickedUp = droppedItem.getItemStack();
        for (ItemStack bag : player.getInventory().getContents()) {
            if (isBag(bag)) {
                ItemMeta meta = bag.getItemMeta();
                byte toggle = meta.getPersistentDataContainer().getOrDefault(bagToggleKey, PersistentDataType.BYTE, (byte) 1);
                if (toggle == 0) continue;

                if (canHold(bag, pickedUp)) {
                    if (addItemToBag(bag, pickedUp)) {
                        droppedItem.remove();
                        String type = meta.getPersistentDataContainer().get(bagTypeKey, PersistentDataType.STRING);
                        String sName = getConfig().getString("bags." + type + ".pickup-sound", "ENTITY_ITEM_PICKUP");
                        player.playSound(player.getLocation(), Sound.valueOf(sName), 0.5f, (float) getConfig().getDouble("bags." + type + ".pickup-pitch", 1.0));
                        refreshLore(bag);
                        return;
                    }
                }
            }
        }
    }

    private boolean addItemToBag(ItemStack bag, ItemStack toAdd) {
        ItemMeta meta = bag.getItemMeta();
        int tier = meta.getPersistentDataContainer().getOrDefault(bagTierKey, PersistentDataType.INTEGER, 1);
        int size = Math.min(tier * 9, 54);
        String encoded = meta.getPersistentDataContainer().get(bagContentKey, PersistentDataType.STRING);
        ItemStack[] contents = (encoded == null || encoded.isEmpty()) ? new ItemStack[size] : deserialize(encoded);

        Inventory temp = Bukkit.createInventory(null, size);
        temp.setContents(contents);
        HashMap<Integer, ItemStack> left = temp.addItem(toAdd);

        if (left.isEmpty() || left.get(0).getAmount() != toAdd.getAmount()) {
            if (!left.isEmpty()) toAdd.setAmount(left.get(0).getAmount());
            meta.getPersistentDataContainer().set(bagContentKey, PersistentDataType.STRING, serialize(temp.getContents()));
            bag.setItemMeta(meta);
            return left.isEmpty();
        }
        return false;
    }

    private boolean isBag(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(bagTypeKey, PersistentDataType.STRING);
    }

    private boolean canHold(ItemStack bag, ItemStack item) {
        String type = bag.getItemMeta().getPersistentDataContainer().get(bagTypeKey, PersistentDataType.STRING);
        return getConfig().getStringList("bags." + type + ".items").contains(item.getType().name());
    }

    private String serialize(ItemStack[] items) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(os);
            out.writeInt(items.length);
            for (ItemStack i : items) out.writeObject(i);
            out.close();
            return Base64Coder.encodeLines(os.toByteArray());
        } catch (Exception e) { return ""; }
    }

    private ItemStack[] deserialize(String data) {
        try {
            ByteArrayInputStream is = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream in = new BukkitObjectInputStream(is);
            ItemStack[] items = new ItemStack[in.readInt()];
            for (int i = 0; i < items.length; i++) items[i] = (ItemStack) in.readObject();
            in.close();
            return items;
        } catch (Exception e) { return new ItemStack[0]; }
    }

    public void togglePickup(Player player, ItemStack bag) {
        ItemMeta meta = bag.getItemMeta();
        byte current = meta.getPersistentDataContainer().getOrDefault(bagToggleKey, PersistentDataType.BYTE, (byte) 1);
        byte newState = (current == 1) ? (byte) 0 : (byte) 1;
        meta.getPersistentDataContainer().set(bagToggleKey, PersistentDataType.BYTE, newState);
        bag.setItemMeta(meta);
        refreshLore(bag);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, newState == 1 ? 1.5f : 0.5f);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mmobags.admin")) {
            sender.sendMessage(color("&#FF5555You do not have permission!"));
            return true;
        }
        if (!(sender instanceof Player p)) return true;

        if (label.equalsIgnoreCase("bagcreate")) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR) {
                p.sendMessage(color("&#FF5555You must hold an item!"));
                return true;
            }
            if (args.length < 2) return false;
            String type = args[0].toUpperCase();
            ensureConfigEntry(type);
            ItemMeta meta = hand.getItemMeta();
            meta.getPersistentDataContainer().set(bagTypeKey, PersistentDataType.STRING, type);
            meta.getPersistentDataContainer().set(bagTierKey, PersistentDataType.INTEGER, Integer.parseInt(args[1]));
            meta.getPersistentDataContainer().set(bagUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            hand.setItemMeta(meta);
            refreshLore(hand);
            p.sendMessage(color("&#55FF55Bag created!"));
        }
        else if (label.equalsIgnoreCase("bagadditem")) {
            if (args.length < 1) return false;
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR) return true;
            String type = args[0].toUpperCase();
            ensureConfigEntry(type);
            List<String> items = getConfig().getStringList("bags." + type + ".items");
            if (!items.contains(hand.getType().name())) {
                items.add(hand.getType().name());
                getConfig().set("bags." + type + ".items", items);
                saveConfig();
                p.sendMessage(color("&#55FF55Item added!"));
            }
        }
        else if (label.equalsIgnoreCase("bageditlore")) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!isBag(hand) || args.length < 1) return true;
            ItemMeta meta = hand.getItemMeta();
            meta.getPersistentDataContainer().set(bagLoreKey, PersistentDataType.STRING, args[0]);
            hand.setItemMeta(meta);
            refreshLore(hand);
            p.sendMessage(color("&#55FF55Lore updated!"));
        }
        else if (label.equalsIgnoreCase("baggive")) {
            if (args.length < 3) return false;
            Player t = Bukkit.getPlayer(args[0]);
            if (t != null) {
                String type = args[1].toUpperCase();
                ensureConfigEntry(type);
                ItemStack bag = new ItemStack(Material.BUNDLE);
                ItemMeta meta = bag.getItemMeta();
                meta.getPersistentDataContainer().set(bagTypeKey, PersistentDataType.STRING, type);
                meta.getPersistentDataContainer().set(bagTierKey, PersistentDataType.INTEGER, Integer.parseInt(args[2]));
                meta.getPersistentDataContainer().set(bagUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
                bag.setItemMeta(meta);
                refreshLore(bag);
                t.getInventory().addItem(bag);
            }
        }
        else if (label.equalsIgnoreCase("bagreload")) {
            reloadConfig();
            createMessagesConfig();
            p.sendMessage(color("&#55FF55MMOBags Reloaded!"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && !command.getName().equalsIgnoreCase("bagreload")) {
            ConfigurationSection s = getConfig().getConfigurationSection("bags");
            return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
        }
        return new ArrayList<>();
    }
}