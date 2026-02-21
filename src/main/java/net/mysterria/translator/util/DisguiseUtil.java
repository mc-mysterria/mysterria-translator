package net.mysterria.translator.util;

import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.Disguise;
import me.libraryaddict.disguise.disguisetypes.DisguiseType;
import me.libraryaddict.disguise.disguisetypes.MobDisguise;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.LivingWatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

public class DisguiseUtil {

    private static boolean isLibsDisguisesLoaded() {
        return Bukkit.getPluginManager().getPlugin("LibsDisguises") != null;
    }

    /**
     * Returns the name that should appear in chat for this player.
     * If LibsDisguises is present and the player is disguised as another player,
     * returns the disguise name; otherwise returns the real player name.
     *
     * @param player The player to resolve the chat name for
     * @return The disguise name if applicable, otherwise the player's real name
     */
    public static String getChatName(Player player) {
        if (!isLibsDisguisesLoaded()) {
            return player.getName();
        }

        if (DisguiseAPI.isDisguised(player)) {
            Disguise disguise = DisguiseAPI.getDisguise(player);
            if (disguise instanceof PlayerDisguise playerDisguise) {
                return playerDisguise.getName();
            }
        }

        return player.getName();
    }

    public static boolean isDisguised(Player player) {
        return DisguiseAPI.isDisguised(player);
    }

    public static String getDisguiseName(Player player) {
        if (isDisguised(player)) {
            Disguise disguise = DisguiseAPI.getDisguise(player);
            return disguise.getDisguiseName();
        }

        return null;
    }

    /**
     * Applies a player disguise to the given player
     *
     * @param player     The player to disguise
     * @param targetName The name of the player to disguise as
     */
    public static void applyPlayerDisguise(Player player, String targetName) {
        PlayerDisguise disguise = new PlayerDisguise(targetName);

        disguise.setEntity(player);
        disguise.setKeepDisguiseOnPlayerDeath(true);
        disguise.setNameVisible(true);

        DisguiseAPI.disguiseEntity(player, disguise);
    }

    public static void applyPlayerDisguise(Entity entity, String targetName) {
        PlayerDisguise disguise = new PlayerDisguise(targetName);

        disguise.setEntity(entity);
        disguise.setKeepDisguiseOnPlayerDeath(true);
        disguise.setNameVisible(false);

        DisguiseAPI.disguiseEntity(entity, disguise);
    }

    /**
     * Applies an entity disguise to the given player
     *
     * @param player     The player to disguise
     * @param entityType The entity type to disguise as
     */
    public static void applyEntityDisguise(Player player, EntityType entityType) {
        DisguiseType disguiseType = convertEntityType(entityType);

        MobDisguise disguise = new MobDisguise(disguiseType);

        disguise.setEntity(player);
        disguise.setKeepDisguiseOnPlayerDeath(true);
        disguise.setViewSelfDisguise(true);

        DisguiseAPI.disguiseEntity(player, disguise);
    }

    public static Disguise getDisguise(Player player) {
        return DisguiseAPI.getDisguise(player);
    }

    /**
     * Removes any disguise from the player
     *
     * @param player The player to undisguise
     */
    public static void removeDisguise(Player player) {
        if (DisguiseAPI.isDisguised(player)) {
            Disguise disguise = DisguiseAPI.getDisguise(player);
            if (disguise != null) {
                disguise.removeDisguise();
            }
        }
    }

    /**
     * Updates the equipment shown on a disguised entity.
     * Call this after changing equipment on an entity that has a player disguise.
     *
     * @param entity The entity with a disguise
     */
    public static void updateDisguiseEquipment(Entity entity) {
        if (!DisguiseAPI.isDisguised(entity)) {
            return;
        }

        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        Disguise disguise = DisguiseAPI.getDisguise(entity);
        if (disguise == null) {
            return;
        }

        // Get the disguise watcher (handles what the disguise looks like)
        if (disguise.getWatcher() instanceof LivingWatcher watcher) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                // Sync all equipment slots to the disguise
                watcher.setArmor(new ItemStack[]{
                        equipment.getBoots(),
                        equipment.getLeggings(),
                        equipment.getChestplate(),
                        equipment.getHelmet()}
                );
                watcher.setItemInMainHand(equipment.getItemInMainHand());
                watcher.setItemInOffHand(equipment.getItemInOffHand());
            }
        }
    }

    /**
     * Converts Bukkit EntityType to Lib's Disguise DisguiseType
     *
     * @param entityType The Bukkit EntityType
     * @return The corresponding DisguiseType
     */
    private static DisguiseType convertEntityType(EntityType entityType) {
        return DisguiseType.getType(entityType);
    }

}