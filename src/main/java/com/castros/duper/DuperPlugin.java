package com.castros.duper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;

/**
 * Mecanismo duper:
 *  - Atril con un libro firmado titulado "duper" (1 item) o "stackduper" (stack completo).
 *  - Comparador delante del atril (activa el mecanismo).
 *  - Marcos de items cerca del atril (radio configurable).
 *
 * Con el comparador puesto, al poner un item en un marco NO se gasta el de tu mano.
 * Para duplicar: quita el comparador y luego saca el item del marco (golpeándolo).
 * Si sacas el item con el comparador todavía puesto, el item desaparece sin duplicarse.
 */
public class DuperPlugin extends JavaPlugin implements Listener {

    private NamespacedKey ghostKey;
    private String singleTitle;
    private String stackTitle;
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
        radius = Math.max(1, getConfig().getInt("radius", 3));
        blacklist.clear();
        for (String name : getConfig().getStringList("blacklist")) {
            Material m = Material.matchMaterial(name);
            if (m != null) blacklist.add(m);
            else getLogger().warning("Material desconocido en blacklist: " + name);
        }
    }

    // ---------- Detección del mecanismo ----------

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

    private boolean isBlacklisted(ItemStack item) {
        return Tag.SHULKER_BOXES.isTagged(item.getType()) || blacklist.contains(item.getType());
    }

    // ---------- Poner el item en el marco (sin gastar el de la mano) ----------

    @EventHandler(ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame frame)) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!frame.getItem().getType().isAir()) return; // solo marcos vacíos

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return;

        Setup setup = findSetup(frame.getLocation());
        if (setup == null || !comparatorInFront(setup.lectern())) return;
        if (!p.hasPermission("duper.use")) return;
        if (isBlacklisted(hand)) {
            p.sendMessage("Ese item no se puede duplicar.");
            return;
        }

        e.setCancelled(true); // no se consume el item de la mano

        ItemStack stored = hand.clone();
        if (!setup.stack()) stored.setAmount(1);

        ItemStack visual = hand.clone();
        visual.setAmount(1);
        frame.setItem(visual, false);
        frame.getPersistentDataContainer().set(ghostKey, PersistentDataType.BYTE_ARRAY, stored.serializeAsBytes());
    }

    // ---------- Sacar el item del marco ----------

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
}
