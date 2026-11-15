package com.castros.duper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;

/**
 * Dos mecanismos:
 *
 * 1) DUPER DE ATRIL
 *    - Atril con un libro firmado titulado "duper" (1 item) o "stackduper" (stack completo).
 *    - Comparador delante del atril (activa el mecanismo).
 *    - Marcos de items cerca del atril (radio configurable).
 *    Con el comparador puesto, al poner un item en un marco NO se gasta el de tu mano.
 *    Para duplicar: quita el comparador y luego saca el item del marco (golpeándolo).
 *
 * 2) BARRIL "stack" (item shadowing)
 *    Un barril renombrado "stack" en el yunque. Todo lo que metes en él es una COPIA:
 *    el stack de tu mano / inventario NO se gasta. Luego sacas las copias normalmente.
 */
public class DuperPlugin extends JavaPlugin implements Listener {

    private NamespacedKey ghostKey;
    private String singleTitle;
    private String stackTitle;
    private String barrelName;
    private int radius;
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);

    private record Setup(Block lectern, boolean stack) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ghostKey = new NamespacedKey(this, "ghost");
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadSettings() {
        reloadConfig();
        singleTitle = getConfig().getString("single-title", "duper");
        stackTitle = getConfig().getString("stack-title", "stackduper");
        barrelName = getConfig().getString("barrel-name", "stack");
        radius = Math.max(1, getConfig().getInt("radius", 3));
        blacklist.clear();
        for (String name : getConfig().getStringList("blacklist")) {
            Material m = Material.matchMaterial(name);
            if (m != null) blacklist.add(m);
            else getLogger().warning("Material desconocido en blacklist: " + name);
        }
    }

    private boolean isBlacklisted(ItemStack item) {
        return item == null || item.getType().isAir() || blacklist.contains(item.getType());
    }

    // =====================================================================
    //  1) DUPER DE ATRIL
    // =====================================================================

    @SuppressWarnings("deprecation")
    private String bookTitle(Block b) {
        if (b.getType() != Material.LECTERN) return null;
        if (!(b.getState() instanceof Lectern lectern)) return null;
        ItemStack book = lectern.getInventory().getItem(0);
        if (book == null || book.getType() != Material.WRITTEN_BOOK) return null;
        if (!(book.getItemMeta() instanceof BookMeta meta)) return null;
        return meta.getTitle();
    }

    private boolean comparatorInFront(Block lectern) {
        if (!(lectern.getBlockData() instanceof Directional d)) return false;
        return lectern.getRelative(d.getFacing()).getType() == Material.COMPARATOR;
    }

    private Setup findSetup(Location loc) {
        Block center = loc.getBlock();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = center.getRelative(x, y, z);
                    String title = bookTitle(b);
                    if (title == null) continue;
                    if (title.equalsIgnoreCase(stackTitle)) return new Setup(b, true);
                    if (title.equalsIgnoreCase(singleTitle)) return new Setup(b, false);
                }
            }
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame frame)) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!frame.getItem().getType().isAir()) return; // solo marcos vacíos

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isBlacklisted(hand)) return;

        Setup setup = findSetup(frame.getLocation());
        if (setup == null || !comparatorInFront(setup.lectern())) return;
        if (!p.hasPermission("duper.use")) return;

        e.setCancelled(true); // no se consume el item de la mano

        ItemStack stored = hand.clone();
        if (!setup.stack()) stored.setAmount(1);

        ItemStack visual = hand.clone();
        visual.setAmount(1);
        frame.setItem(visual, false);
        frame.getPersistentDataContainer().set(ghostKey, PersistentDataType.BYTE_ARRAY, stored.serializeAsBytes());
    }

    /** Devuelve true si el marco tenía un item "fantasma" y ya fue liberado. */
    private boolean releaseGhost(ItemFrame frame) {
        byte[] data = frame.getPersistentDataContainer().get(ghostKey, PersistentDataType.BYTE_ARRAY);
        if (data == null) return false;

        frame.getPersistentDataContainer().remove(ghostKey);
        frame.setItem(null, false);

        Setup setup = findSetup(frame.getLocation());
        boolean active = setup != null && comparatorInFront(setup.lectern());
        if (!active) {
            // Comparador quitado: aquí ocurre la duplicación
            frame.getWorld().dropItemNaturally(frame.getLocation(), ItemStack.deserializeBytes(data));
        }
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ItemFrame frame)) return;
        if (releaseGhost(frame)) e.setCancelled(true);
    }

    @EventHandler
    public void onFrameBreak(HangingBreakEvent e) {
        if (e.getEntity() instanceof ItemFrame frame) releaseGhost(frame);
    }

    // =====================================================================
    //  2) BARRIL "stack" (item shadowing)
    // =====================================================================

    private boolean isStackBarrel(Inventory inv) {
        if (!(inv.getHolder(false) instanceof Barrel barrel)) return false;
        Component name = barrel.customName();
        if (name == null) return false;
        String plain = PlainTextComponentSerializer.plainText().serialize(name).trim();
        return plain.equalsIgnoreCase(barrelName);
    }

    private void addCopy(Inventory inv, ItemStack item) {
        int slot = inv.firstEmpty();
        if (slot >= 0) inv.setItem(slot, item);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBarrelClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!isStackBarrel(top)) return;
        if (!p.hasPermission("duper.use")) return;

        int raw = e.getRawSlot();
        int size = top.getSize();
        InventoryAction action = e.getAction();

        // Shift-click desde tu inventario hacia el barril: se copia, no se mueve
        if (raw >= size) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                ItemStack cur = e.getCurrentItem();
                if (isBlacklisted(cur)) return;
                e.setCancelled(true);
                addCopy(top, cur.clone());
                p.updateInventory();
            }
            return;
        }

        // Clic dentro del barril con un item en el cursor / hotbar: se copia al slot
        ItemStack cursor = e.getCursor();
        switch (action) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> {
                if (isBlacklisted(cursor)) return;
                e.setCancelled(true);
                ItemStack copy = cursor.clone();
                if (action == InventoryAction.PLACE_ONE) copy.setAmount(1);
                top.setItem(raw, copy);
                p.updateInventory();
            }
            case HOTBAR_SWAP -> {
                int btn = e.getHotbarButton();
                if (btn < 0) return;
                ItemStack hb = p.getInventory().getItem(btn);
                if (isBlacklisted(hb)) return;
                e.setCancelled(true);
                top.setItem(raw, hb.clone());
                p.updateInventory();
            }
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBarrelDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!isStackBarrel(top)) return;
        if (!p.hasPermission("duper.use")) return;
        int size = top.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < size) {
                e.setCancelled(true); // arrastrar no se soporta en el barril duper
                return;
            }
        }
    }
}
