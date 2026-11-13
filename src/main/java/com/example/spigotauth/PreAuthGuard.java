package com.example.spigotauth;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import java.util.function.BooleanSupplier;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks unauthenticated players without removing them from TAB.
 * Entity invisibility is used instead of hidePlayer so the player-list entry
 * remains visible to clients.
 */
public final class PreAuthGuard {

    private final SpigotAuth plugin;
    private final Map<UUID, GuardState> snapshots = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> approvedTeleports = ConcurrentHashMap.newKeySet();

    public PreAuthGuard(SpigotAuth plugin) {
        this.plugin = plugin;
    }

    public boolean isActive(Player player) {
        GuardState state = snapshots.get(player.getUniqueId());
        return state != null && state.owner() == player;
    }

    public void activate(Player player) {
        snapshots.compute(player.getUniqueId(), (id, state) ->
                state != null && state.owner() == player ? state : new GuardState(
                        player, new Snapshot(
                                player.getGameMode(),
                                player.isInvisible(),
                                player.isInvulnerable(),
                                player.isCollidable(),
                                player.getCanPickupItems(),
                                player.getAllowFlight(),
                                player.isFlying(),
                                player.isGlowing(),
                                player.isSilent()
                        )));

        player.setGameMode(GameMode.SURVIVAL);
        player.setInvisible(true);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setCanPickupItems(false);
        player.setGlowing(false);
        player.setSilent(true);
        if (player.isInsideVehicle()) player.leaveVehicle();
        if (player.isFlying()) player.setFlying(false);
        player.setAllowFlight(false);
        // Do not zero vertical motion: airborne reconnects must fall naturally.
        player.setFallDistance(0.0F);

        maskEquipment(player);
    }

    public void deactivate(Player player) {
        GuardState state = snapshots.get(player.getUniqueId());
        if (state == null || state.owner() != player
                || !snapshots.remove(player.getUniqueId(), state)) return;
        Snapshot snapshot = state.snapshot();

        player.setInvisible(snapshot.invisible());
        player.setInvulnerable(snapshot.invulnerable());
        player.setCollidable(snapshot.collidable());
        player.setCanPickupItems(snapshot.canPickupItems());
        player.setGlowing(snapshot.glowing());
        player.setSilent(snapshot.silent());
        player.setGameMode(snapshot.gameMode());
        player.setAllowFlight(snapshot.allowFlight());
        if (snapshot.allowFlight()) player.setFlying(snapshot.flying());

        restoreEquipment(player);
    }

    public void forget(Player player) {
        GuardState state = snapshots.get(player.getUniqueId());
        if (state != null && state.owner() == player) {
            snapshots.remove(player.getUniqueId(), state);
        }
    }

    /** Authorizes only SLogin's synchronous internal relocation. */
    public boolean withAuthorizedTeleport(Player player, BooleanSupplier operation) {
        if (!isActive(player)) return operation.getAsBoolean();
        UUID id = player.getUniqueId();
        boolean added = approvedTeleports.add(id);
        try {
            return operation.getAsBoolean();
        } finally {
            if (added) approvedTeleports.remove(id);
        }
    }

    public boolean isTeleportAuthorized(Player player) {
        return isActive(player) && approvedTeleports.contains(player.getUniqueId());
    }

    public void refreshViewer(Player viewer) {
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (isActive(target) && !viewer.getUniqueId().equals(target.getUniqueId())) {
                sendMaskedEquipment(viewer, target);
            }
        }
    }

    private void maskEquipment(Player target) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(target.getUniqueId())) {
                sendMaskedEquipment(viewer, target);
            }
        }
    }

    private void restoreEquipment(Player target) {
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        if (target.getEquipment() != null) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    ItemStack item = target.getEquipment().getItem(slot);
                    // Empty inventory and armor slots are null on Bukkit.
                    // Never let a null item abort pre-auth cleanup.
                    equipment.put(slot, copyOrAir(item));
                } catch (IllegalArgumentException ignored) {
                    // Some enum values are not applicable to players on every API revision.
                }
            }
        }

        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(target.getUniqueId()) && !equipment.isEmpty()) {
                viewer.sendEquipmentChange(target, equipment);
            }
        }
    }

    static ItemStack copyOrAir(ItemStack item) {
        return item == null ? new ItemStack(Material.AIR) : item.clone();
    }

    private static void sendMaskedEquipment(Player viewer, Player target) {
        Map<EquipmentSlot, ItemStack> hidden = new EnumMap<>(EquipmentSlot.class);
        ItemStack air = new ItemStack(Material.AIR);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                hidden.put(slot, air);
            } catch (IllegalArgumentException ignored) {
            }
        }
        viewer.sendEquipmentChange(target, hidden);
    }

    private record GuardState(Player owner, Snapshot snapshot) {}

    private record Snapshot(
            GameMode gameMode,
            boolean invisible,
            boolean invulnerable,
            boolean collidable,
            boolean canPickupItems,
            boolean allowFlight,
            boolean flying,
            boolean glowing,
            boolean silent
    ) {}
}
