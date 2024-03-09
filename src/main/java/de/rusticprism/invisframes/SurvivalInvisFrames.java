package de.rusticprism.invisframes;

import com.google.common.collect.Lists;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class SurvivalInvisFrames extends JavaPlugin implements Listener {
    private static NamespacedKey invisibleKey;
    private static NamespacedKey invisibleRecipe;

    private static boolean framesGlow;
    private static boolean firstLoad = true;

    // Stays null if not in 1.17
    private static Material glowFrame = null;
    private static EntityType glowFrameEntity = null;

    public static ItemStack generateInvisibleItemFrame() {
        ItemStack item = new ItemStack(Material.ITEM_FRAME, 1);
        ItemMeta meta = item.getItemMeta();
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.getPersistentDataContainer().set(invisibleKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void onEnable() {
        invisibleRecipe = new NamespacedKey(this, "invisible-recipe");
        invisibleKey = new NamespacedKey(this, "invisible");

        try {
            glowFrame = Material.valueOf("GLOW_ITEM_FRAME");
            glowFrameEntity = EntityType.valueOf("GLOW_ITEM_FRAME");
        } catch (IllegalArgumentException ignored) {
        }

        reload();

        getServer().getPluginManager().registerEvents(this, this);
        InvisiFramesCommand invisiFramesCommand = new InvisiFramesCommand(this);
        Objects.requireNonNull(getCommand("iframe")).setExecutor(invisiFramesCommand);
        Objects.requireNonNull(getCommand("iframe")).setTabCompleter(invisiFramesCommand);
    }

    @Override
    public void onDisable() {
        // Remove added recipes on plugin disable
        removeRecipe();
    }

    private void removeRecipe() {
        Iterator<Recipe> iter = getServer().recipeIterator();
        while (iter.hasNext()) {
            Recipe check = iter.next();
            if (isInvisibleRecipe(check)) {
                iter.remove();
                break;
            }
        }
    }

    public void setRecipeItem(ItemStack item) {
        getConfig().set("recipe", item);
        saveConfig();
        reload();
    }

    public void reload() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        removeRecipe();

        if (firstLoad) {
            firstLoad = false;
            framesGlow = !getConfig().getBoolean("item-frames-glow");
        }
        if (getConfig().getBoolean("item-frames-glow") != framesGlow) {
            framesGlow = getConfig().getBoolean("item-frames-glow");
            forceRecheck();
        }

        ItemStack invisibleItem = generateInvisibleItemFrame();
        invisibleItem.setAmount(8);

        ShapedRecipe recipe = new ShapedRecipe(invisibleRecipe, invisibleItem);
        recipe.shape("FFF", "FPF", "FFF");

        if (glowFrame != null) {
            recipe.setIngredient('F', new RecipeChoice.MaterialChoice(Material.ITEM_FRAME, glowFrame));
        } else {
            recipe.setIngredient('F', Material.ITEM_FRAME);
        }

        ItemStack invisibilityPotion = getConfig().getItemStack("recipe");
        assert invisibilityPotion != null;
        recipe.setIngredient('P', new RecipeChoice.MaterialChoice(invisibilityPotion.getType()));

        Bukkit.addRecipe(recipe);
    }

    public void forceRecheck() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemFrame frame : world.getEntitiesByClass(ItemFrame.class)) {
                if (frame.getPersistentDataContainer().has(invisibleKey, PersistentDataType.BYTE)) {
                    if (frame.getItem().getType() == Material.AIR && framesGlow) {
                        frame.setGlowing(true);
                        frame.setVisible(true);
                    } else if (frame.getItem().getType() != Material.AIR) {
                        frame.setGlowing(false);
                        frame.setVisible(false);
                    }
                }
            }
        }
    }

    private boolean isInvisibleRecipe(Recipe recipe) {
        return recipe instanceof ShapedRecipe shapedRecipe && shapedRecipe.getKey().equals(invisibleRecipe);
    }

    private boolean isFrameEntity(Entity entity) {
        return (entity != null && (entity.getType() == EntityType.ITEM_FRAME ||
                (glowFrameEntity != null && entity.getType() == glowFrameEntity)));
    }

    @EventHandler(ignoreCancelled = true)
    private void onCraft(PrepareItemCraftEvent event) {
        if (isInvisibleRecipe(event.getRecipe())) {
            CraftingInventory crafting = event.getInventory();

            if (!event.getView().getPlayer().hasPermission("survivalinvisiframes.craft")) {
                crafting.setResult(null);
                return;
            }

            List<ItemStack> matrix = Lists.newArrayList(crafting.getMatrix());
            ItemStack resultItem = crafting.getResult();

            if (resultItem.getItemMeta() instanceof PotionMeta resultItemMeta) {
                PotionMeta craftPotionMeta = (PotionMeta) matrix.get(4).getItemMeta();

                if (resultItemMeta.getBasePotionData().getType() != craftPotionMeta.getBasePotionData().getType()) {
                    crafting.setResult(null);
                    return;
                }
            }

            if (glowFrame != null) {
                matrix.remove(4);

                if (matrix.stream().allMatch(item -> item.getType() == glowFrame)) {
                    ItemStack frame = crafting.getResult();
                    frame.setType(glowFrame);
                    crafting.setResult(frame);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    private void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();

        if (player == null) {
            return;
        }

        Entity entity = event.getEntity();

        if (!isFrameEntity(entity)) {
            return;
        }

        // Get the frame item that the player placed
        ItemStack frame;
        if (player.getInventory().getItemInMainHand().getType() == Material.ITEM_FRAME ||
                (glowFrame != null && player.getInventory().getItemInMainHand().getType() == glowFrame)) {

            frame = player.getInventory().getItemInMainHand();
        } else if (player.getInventory().getItemInOffHand().getType() == Material.ITEM_FRAME ||
                (glowFrame != null && player.getInventory().getItemInOffHand().getType() == glowFrame)) {

            frame = player.getInventory().getItemInOffHand();
        } else {
            return;
        }

        // If the frame item has the invisible tag, make the placed item frame invisible
        if (frame.getItemMeta().getPersistentDataContainer().has(invisibleKey, PersistentDataType.BYTE)) {
            if (!player.hasPermission("survivalinvisiframes.place")) {
                event.setCancelled(true);
                return;
            }

            ItemFrame itemFrame = (ItemFrame) event.getEntity();

            if (framesGlow) {
                itemFrame.setVisible(true);
                itemFrame.setGlowing(true);
            } else {
                itemFrame.setVisible(false);
            }

            entity.getPersistentDataContainer().set(invisibleKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    private void onHangingBreak(HangingBreakEvent event) {
        Entity entity = event.getEntity();

        if (!isFrameEntity(entity) || !entity.getPersistentDataContainer().has(invisibleKey, PersistentDataType.BYTE)) {
            return;
        }

        ItemStack frame = generateInvisibleItemFrame();

        if (entity.getType() == glowFrameEntity) {
            frame.setType(glowFrame);
        }

        event.setCancelled(true);
        entity.getWorld().dropItemNaturally(entity.getLocation(), frame);
        entity.remove();
    }

    @EventHandler(ignoreCancelled = true)
    private void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!framesGlow) {
            return;
        }

        if (isFrameEntity(event.getRightClicked()) &&
                event.getRightClicked().getPersistentDataContainer().has(invisibleKey, PersistentDataType.BYTE)) {

            ItemFrame frame = (ItemFrame) event.getRightClicked();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (frame.getItem().getType() != Material.AIR) {
                    frame.setGlowing(false);
                    frame.setVisible(false);
                }
            }, 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    private void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!framesGlow) {
            return;
        }

        if (isFrameEntity(event.getEntity()) &&
                event.getEntity().getPersistentDataContainer().has(invisibleKey, PersistentDataType.BYTE)) {

            ItemFrame frame = (ItemFrame) event.getEntity();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (frame.getItem().getType() == Material.AIR && framesGlow) {
                    frame.setGlowing(true);
                    frame.setVisible(true);
                }
            }, 1L);
        }
    }
}