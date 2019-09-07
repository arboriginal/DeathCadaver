package me.arboriginal.DeathCadaver;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

public class DCPlugin extends JavaPlugin implements Listener, TabCompleter {
  private FileConfiguration                        config;
  private BukkitTask                               task     = null;
  private final String                             DCfolder = "cadavers";
  private final NamespacedKey                      deathKNS = new NamespacedKey(this, "DC_datetime");
  private final NamespacedKey                      ownerKNS = new NamespacedKey(this, "DC_owner");
  private final PersistentDataType<Long, Long>     deathKDT = PersistentDataType.LONG;
  private final PersistentDataType<String, String> ownerKDT = PersistentDataType.STRING;

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (command.getName().equalsIgnoreCase("dc-reload")) {
      reloadConfig();
      sendMessage(sender, "configuration_reloaded");
      return true;
    }

    if (command.getName().equalsIgnoreCase("dc-cleanup")) {
      int olderthan = (args.length == 2 && StringUtils.isNumeric(args[1]))
          ? Integer.parseInt(args[1])
          : config.getInt("cleanup.command.default_olderthan");

      World world  = null;
      int   radius = 0;

      if (args.length > 0) {
        if (!StringUtils.isNumeric(args[0]))
          world = getServer().getWorld(args[0]);
        else if (sender instanceof Player && args.length < 3) {
          world  = ((Player) sender).getWorld();
          radius = Integer.parseInt(args[0]);

          if (radius > config.getInt("cleanup.command.max_radius")) {
            sendMessage(sender, "error_radius_too_big");
            return false;
          }

          cleanup(olderthan, (Player) sender, radius);
          return true;
        }
        else
          radius = -1;
      }

      if (args.length > 2 || radius < 0 || (args.length > 0 && world == null)
          || (args.length == 2 && !StringUtils.isNumeric(args[1]))) {
        sendMessage(sender, "error_wrong_arguments");
        return false;
      }

      if (world != null)
        cleanup(olderthan, sender, world);
      else
        cleanup(olderthan, sender);

      return true;
    }

    return super.onCommand(sender, command, label, args);
  }

  @Override
  public void onDisable() {
    super.onDisable();
    if (task != null && !task.isCancelled()) task.cancel();
    HandlerList.unregisterAll((JavaPlugin) this);
  }

  @Override
  public void onEnable() {
    super.onEnable();
    String error = null;

    try {
      getServer().spigot();
    }
    catch (Exception e) {
      error = "This plugin only works on Spigot servers!";
    }

    if (error == null) error = createFolders();
    if (error != null) {
      getLogger().severe(error);
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    reloadConfig();

    if (config.getBoolean("cleanup.periodically.enabled")) {
      task = new BukkitRunnable() {
        @Override
        public void run() {
          if (isCancelled()) return;
          cleanup(config.getInt("cleanup.periodically.olderthan"));
        }
      }.runTaskTimer(this,
          20 * config.getInt("cleanup.periodically.wait"),
          20 * config.getInt("cleanup.periodically.frequency"));
    }

    getServer().getPluginManager().registerEvents(this, this);
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0 || !(sender instanceof Player)) return null;

    if (args.length == 1 && command.getName().equals("dc-cleanup")) {
      List<String> list = new ArrayList<String>();

      for (World world : getServer().getWorlds()) {
        String name = world.getName();
        if (name.toLowerCase().startsWith(args[0].toLowerCase())) list.add(name);
      }

      return list;
    }

    return null;
  }

  @Override
  public void reloadConfig() {
    super.reloadConfig();

    saveDefaultConfig();
    config = getConfig();
    config.options().copyDefaults(true);
    saveConfig();
  }

  //-----------------------------------------------------------------------------------------------------------------------

  @EventHandler
  public void onChunkLoadEvent(ChunkLoadEvent event) {
    if (event.isNewChunk()) return;
    long expire = System.currentTimeMillis() - 1000 * config.getInt("cleanup.olderthan");
    for (Entity entity : event.getChunk().getEntities()) {
      if (isCadaver(entity) && getDeath(entity) < expire) cleanupCadaver(entity);
    }
  }

  @EventHandler
  public void onPlayerDeath(PlayerDeathEvent event) {
    if (event.getDrops().size() == 0) return;

    Player player = event.getEntity();
    if (!player.hasPermission("dc.world.*") && !player.hasPermission("dc.world." + player.getWorld().getName())) return;

    ArmorStand cadaver = spawnCadaver(player);
    if (cadaver == null) return;

    long xp = Math.round(player.getTotalExperience() * config.getDouble("cadavers.retain_xp"));
    event.setDroppedExp(0);

    FileConfiguration stacks = new YamlConfiguration();
    stacks.set("death.date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(getDeath(cadaver))));
    stacks.set("death.location", cadaver.getLocation());
    stacks.set("owner.name", player.getName());
    stacks.set("owner.UUID", getOwner(cadaver));
    stacks.set("experience", xp);
    stacks.set("items", event.getDrops());
    File file = getItemsFile(cadaver);

    try {
      stacks.save(file);
    }
    catch (IOException e) {
      HashMap<String, String> placeholders = new HashMap<>();
      placeholders.put("file", file.toString());
      error("creating_file", placeholders);
      sendMessage(player, "error_creating_file_to_player");
      cadaver.remove();
      return;
    }

    clearDrops(event.getDrops());
    customCommands("Death", player, cadaver);
  }

  /**
   * This method is used instead a more "logical" system, because this is the only event
   * I could use in my tests inside protected area (like claims from GriefPrevention plugin).
   * If you find a cleaner way to do it, let me know.
   * 
   * @param event
   */
  @EventHandler
  public void onEntityDamageByEntityEvent(EntityDamageByEntityEvent event) {
    Entity cadaver = event.getEntity();
    if (!(event.getDamager() instanceof Player) || !isCadaver(cadaver)) return;
    event.setCancelled(true);
    Player player = (Player) event.getDamager();
    if (!isLootable(cadaver, player)) return;

    World             world  = cadaver.getWorld();
    Location          loc    = cadaver.getLocation();
    File              file   = getItemsFile(cadaver);
    FileConfiguration stacks = YamlConfiguration.loadConfiguration(file);

    for (Object stack : stacks.getList("items"))
      if (stack instanceof ItemStack) world.dropItem(loc, (ItemStack) stack);
    player.giveExp(stacks.getInt("experience"));

    if (!file.delete()) {
      HashMap<String, String> placeholders = new HashMap<>();
      placeholders.put("file", file.toString());
      error("deleting_file", placeholders);
    }

    cadaver.remove();
    customCommands("BreakCadaver", player, cadaver);
  }

  @EventHandler
  public void onEntityDamageEvent(EntityDamageEvent event) {
    if (event.isCancelled()) return;
    if (isCadaver(event.getEntity())) event.setCancelled(true);
  }

  @EventHandler
  public void onEntityDeathEvent(EntityDeathEvent event) {
    if (isCadaver(event.getEntity())) clearDrops(event.getDrops());
  }

  @EventHandler
  public void onPlayerArmorStandManipulateEvent(PlayerArmorStandManipulateEvent event) {
    if (event.isCancelled() || event.getHand() == EquipmentSlot.OFF_HAND) return;
    if (isCadaver(event.getRightClicked())) event.setCancelled(true);
  }

  @EventHandler
  public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
    if (event.getHand() == EquipmentSlot.OFF_HAND) return;
    if (isCadaver(event.getRightClicked())) event.setCancelled(true);
  }

  // -----------------------------------------------------------------------------------------------------------------------

  private void cleanup(int olderthan) {
    cleanup(olderthan, Bukkit.getConsoleSender());
  }

  private void cleanup(int olderthan, CommandSender sender) {
    for (World world : getServer().getWorlds()) cleanup(olderthan, sender, world);
  }

  private void cleanup(int olderthan, CommandSender sender, World world) {
    File folder = new File(getFolder(world));

    HashMap<String, String> placeholders = new HashMap<>();
    placeholders.put("world", world.getName());

    if (!folder.isDirectory()) {
      placeholders.put("folder", folder.toString());
      error("reading_folder", placeholders);
      return;
    }

    sendMessage(sender, "cleanup_starting", placeholders);

    String         regex  = "^(\\d+)@([0-9\\-]+)\\|([0-9\\-]+)\\|([0-9\\-]+)\\.yml$";
    FilenameFilter filter = new FilenameFilter() {
                            @Override
                            public boolean accept(File dir, String name) {
                              return name.matches(regex);
                            }
                          };

    String[] files   = folder.list(filter);
    int      total   = files.length, notloaded = 0, removed = 0;
    long     expire  = System.currentTimeMillis() - olderthan * 1000;
    Pattern  pattern = Pattern.compile(regex);

    Arrays.sort(files);

    for (String file : files) {
      Matcher matches = pattern.matcher(file);
      if (!matches.find()) continue;

      long deathdate = Long.parseLong(matches.group(1));

      if (deathdate > expire) break;

      placeholders.put("file", world.getName() + "/" + file);

      if (cleanupCadaver(world, deathdate,
          Integer.parseInt(matches.group(2)),
          Integer.parseInt(matches.group(3)),
          Integer.parseInt(matches.group(4)))) {
        if (!new File(folder.getAbsolutePath() + File.separator + file).delete()) {
          placeholders.put("file", world.getName());
          sendMessage(sender, "error_deleting_file", placeholders);
        }
        removed++;
      }
      else
        notloaded++;
    }

    placeholders.put("left", "" + (total - removed));
    placeholders.put("notloaded", "" + notloaded);
    placeholders.put("removed", "" + removed);

    sendMessage(sender, "cleanup_executed", placeholders);
  }

  private void cleanup(int olderthan, Player player, int radius) {
    HashMap<String, String> placeholders = new HashMap<>();
    placeholders.put("world", player.getWorld().getName());
    placeholders.put("radius", "" + radius);

    sendMessage(player, "radius_cleanup_starting", placeholders);

    long expire = System.currentTimeMillis() - 1000 * olderthan;
    int  total  = 0, removed = 0;

    for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
      if (!isCadaver(entity)) continue;
      total++;

      if (getDeath(entity) > expire) continue;
      cleanupCadaver(entity);
      removed++;
    }

    placeholders.put("left", "" + (total - removed));
    placeholders.put("removed", "" + removed);

    sendMessage(player, "radius_cleanup_executed", placeholders);
  }

  private void cleanupCadaver(Entity cadaver) {
    cadaver.remove();

    File file = getItemsFile(cadaver);

    if (!file.delete()) {
      HashMap<String, String> placeholders = new HashMap<>();
      placeholders.put("file", file.toString());
      error("deleting_file", placeholders);
    }
  }

  private boolean cleanupCadaver(World world, long deathdate, int x, int y, int z) {
    for (Entity entity : world.getNearbyEntities(new Location(world, x, y, z), 1, 1, 1)) {
      if (!isCadaver(entity) || Long.compare(getDeath(entity), deathdate) != 0) continue;
      entity.remove();
      return true;
    }

    return false;
  }

  private void customCommands(String event, Player player, Entity cadaver) {
    HashMap<String, String> placeholders = new HashMap<>();
    placeholders.put("player", player.getName());
    placeholders.put("world", cadaver.getWorld().getName());
    placeholders.put("x", "" + cadaver.getLocation().getBlockX());
    placeholders.put("y", "" + cadaver.getLocation().getBlockY());
    placeholders.put("z", "" + cadaver.getLocation().getBlockZ());

    List<?> commandsPlayer = config.getList("events.on" + event + ".commands_as_player");
    List<?> commandsServer = config.getList("events.on" + event + ".commands_as_server");

    if (commandsPlayer != null)
      for (Object command : commandsPlayer)
        player.performCommand(formatMessage(command.toString(), placeholders));

    if (commandsServer != null)
      for (Object command : commandsServer)
        getServer().dispatchCommand(getServer().getConsoleSender(), formatMessage(command.toString(), placeholders));
  }

  // -----------------------------------------------------------------------------------------------------------------------

  private ItemStack cadaverEquipment(Player player, String part, ItemStack equiped) {
    if (!config.getBoolean("cadavers.equipment." + part + ".fixed")
        && equiped != null && !equiped.getType().equals(Material.AIR))
      return equiped;

    Material  type  = Material.valueOf(config.getString("cadavers.equipment." + part + ".item"));
    ItemStack stack = new ItemStack(type);

    if (type.equals(Material.PLAYER_HEAD)) {
      SkullMeta meta = (SkullMeta) stack.getItemMeta();
      meta.setOwningPlayer(player);
      stack.setItemMeta(meta);
    }

    return stack;
  }

  private void clearDrops(List<ItemStack> drops) {
    Iterator<ItemStack> iterator = drops.iterator();

    while (iterator.hasNext()) {
      iterator.next();
      iterator.remove();
    }
  }

  private long getDeath(Entity cadaver) {
    return cadaver.getPersistentDataContainer().get(deathKNS, deathKDT);
  }

  private String getOwner(Entity cadaver) {
    return cadaver.getPersistentDataContainer().get(ownerKNS, ownerKDT);
  }

  private boolean isCadaver(Entity entity) {
    return entity instanceof ArmorStand && entity.getPersistentDataContainer().has(deathKNS, deathKDT);
  }

  private boolean isLootable(Entity cadaver, Player player) {
    return player.hasPermission("dc.bypass_loot_timer")
        || getOwner(cadaver).equals(player.getUniqueId().toString())
        || getDeath(cadaver) < System.currentTimeMillis() + 1000 * config.getInt("cadavers.lootable_after");
  }

  private ArmorStand spawnCadaver(Player player) {
    HashMap<String, String> placeholders = new HashMap<>();
    placeholders.put("player", player.getDisplayName());

    PlayerInventory inv     = player.getInventory();
    Location        loc     = player.getLocation();
    World           world   = player.getWorld();
    ArmorStand      cadaver = world.spawn(loc.add(new Vector(
        config.getDouble("cadavers.position.x"),
        config.getDouble("cadavers.position.y"),
        config.getDouble("cadavers.position.z"))), ArmorStand.class);

    cadaver.getPersistentDataContainer().set(deathKNS, deathKDT, System.currentTimeMillis());
    cadaver.getPersistentDataContainer().set(ownerKNS, ownerKDT, player.getUniqueId().toString());

    cadaver.setArms(config.getBoolean("cadavers.with_arms"));
    cadaver.setBasePlate(config.getBoolean("cadavers.with_base"));
    cadaver.setBodyPose(new EulerAngle(
        config.getDouble("cadavers.pose.body.x"),
        config.getDouble("cadavers.pose.body.y"),
        config.getDouble("cadavers.pose.body.z")));
    cadaver.setBoots(cadaverEquipment(player, "boots", inv.getBoots()));
    cadaver.setChestplate(cadaverEquipment(player, "body", inv.getChestplate()));
    cadaver.setCollidable(false);
    cadaver.setCustomName(formatMessage(config.getString("cadavers.displayed_name"), placeholders));
    cadaver.setCustomNameVisible(config.getBoolean("cadavers.visible_name"));
    cadaver.setGlowing(config.getBoolean("cadavers.glowing"));
    cadaver.setGravity(config.getBoolean("cadavers.gravity"));
    cadaver.setHeadPose(new EulerAngle(
        config.getDouble("cadavers.pose.head.x"),
        config.getDouble("cadavers.pose.head.y"),
        config.getDouble("cadavers.pose.head.z")));
    cadaver.setHelmet(cadaverEquipment(player, "head", inv.getHelmet()));
    // cadaver.setInvulnerable(true); // @note: Do not use this, @see onEntityDamageByEntityEvent()
    cadaver.setItemInHand(cadaverEquipment(player, "hand", inv.getItemInMainHand()));
    cadaver.setLeggings(cadaverEquipment(player, "legs", inv.getLeggings()));
    cadaver.setLeftArmPose(new EulerAngle(
        config.getDouble("cadavers.pose.arm_left.x"),
        config.getDouble("cadavers.pose.arm_left.y"),
        config.getDouble("cadavers.pose.arm_left.z")));
    cadaver.setLeftLegPose(new EulerAngle(
        config.getDouble("cadavers.pose.leg_left.x"),
        config.getDouble("cadavers.pose.leg_left.y"),
        config.getDouble("cadavers.pose.leg_left.z")));
    cadaver.setRightArmPose(new EulerAngle(
        config.getDouble("cadavers.pose.arm_right.x"),
        config.getDouble("cadavers.pose.arm_right.y"),
        config.getDouble("cadavers.pose.arm_right.z")));
    cadaver.setRightLegPose(new EulerAngle(
        config.getDouble("cadavers.pose.leg_right.x"),
        config.getDouble("cadavers.pose.leg_right.y"),
        config.getDouble("cadavers.pose.leg_right.z")));
    cadaver.setMarker(false);
    cadaver.setSmall(config.getBoolean("cadavers.small"));
    cadaver.setVisible(true);

    return cadaver;
  }

  // -----------------------------------------------------------------------------------------------------------------------

  private boolean createFolder(File folder) {
    if (!folder.exists()) folder.mkdirs();
    return folder.exists() && folder.isDirectory();
  }

  private String createFolders() {
    if (!createFolder(new File(getDataFolder(), DCfolder))) return "Unable to create root cadavers folder...";

    for (World world : getServer().getWorlds())
      if (!createFolder(new File(getFolder(world))))
        return "Unable to create cadavers folder « " + world.getName() + " »...";

    return null;
  }

  private String getFolder(World world) {
    return getDataFolder().getAbsolutePath() + File.separator + DCfolder + File.separator + world.getName();
  }

  private File getItemsFile(Entity cadaver) {
    Location loc = cadaver.getLocation();
    return new File(getFolder(cadaver.getWorld()),
        getDeath(cadaver) + "@" + loc.getBlockX() + "|" + loc.getBlockY() + "|" + loc.getBlockZ() + ".yml");
  }

  // -----------------------------------------------------------------------------------------------------------------------

  private void error(String key, Map<String, String> placeholders) {
    getLogger().severe(formatMessage(config.getString("messages.error_" + key), placeholders));
  }

  private String formatMessage(String message, Map<String, String> placeholders) {
    for (Iterator<String> i = placeholders.keySet().iterator(); i.hasNext();) {
      String placeholder = i.next();
      message = message.replace("{" + placeholder + "}", placeholders.get(placeholder));
    }

    return ChatColor.translateAlternateColorCodes('&', message);
  }

  private void sendMessage(CommandSender sender, String key) {
    sendMessage(sender, key, new HashMap<String, String>());
  }

  private void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
    if (key.isEmpty()) return;

    String message = config.getString("messages." + key);
    if (message.isEmpty()) return;

    placeholders.put("prefix", config.getString("messages.prefix"));

    message = formatMessage(message, placeholders);
    if (!message.isEmpty()) sender.sendMessage(message);
  }
}
