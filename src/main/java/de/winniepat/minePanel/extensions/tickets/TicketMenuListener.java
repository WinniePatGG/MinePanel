package de.winniepat.minePanel.extensions.tickets;

import de.winniepat.minePanel.logs.PanelLogger;
import de.winniepat.minePanel.persistence.KnownPlayerRepository;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TicketMenuListener implements Listener {

    private static final String MENU_TITLE = ChatColor.DARK_AQUA + "MinePanel Tickets";

    private final TicketRepository ticketRepository;
    private final KnownPlayerRepository knownPlayerRepository;
    private final PanelLogger panelLogger;

    public TicketMenuListener(TicketRepository ticketRepository, KnownPlayerRepository knownPlayerRepository, PanelLogger panelLogger) {
        this.ticketRepository = ticketRepository;
        this.knownPlayerRepository = knownPlayerRepository;
        this.panelLogger = panelLogger;
    }

    public void openMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, MENU_TITLE);

        menu.setItem(11, createTicketItem(Material.REDSTONE, ChatColor.RED + "Technical Issue", List.of(ChatColor.GRAY + "Server lag, crashes or bugs")));
        menu.setItem(13, createTicketItem(Material.PAPER, ChatColor.AQUA + "Question / Help", List.of(ChatColor.GRAY + "Need support from the team")));
        menu.setItem(15, createTicketItem(Material.BOOK, ChatColor.GREEN + "Other", List.of(ChatColor.GRAY + "General support request")));
        menu.setItem(22, createTicketItem(Material.BARRIER, ChatColor.RED + "Close", List.of(ChatColor.GRAY + "Close this menu")));

        player.openInventory(menu);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!MENU_TITLE.equals(event.getView().getTitle())) {
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        Map<Integer, TicketTemplate> templates = Map.of(
                11, new TicketTemplate("Technical Issue", "Reported from in-game menu: technical issue"),
                13, new TicketTemplate("Question / Help", "Reported from in-game menu: question or support needed"),
                15, new TicketTemplate("Other", "Reported from in-game menu: general support request")
        );

        if (slot == 22) {
            player.closeInventory();
            return;
        }

        TicketTemplate template = templates.get(slot);
        if (template == null) {
            return;
        }

        long now = Instant.now().toEpochMilli();
        knownPlayerRepository.upsert(player.getUniqueId(), player.getName(), now);

        long ticketId = ticketRepository.createTicket(
                player.getUniqueId(),
                player.getName(),
                template.category(),
                template.description(),
                now
        );

        player.closeInventory();
        player.sendMessage(ChatColor.GREEN + "Ticket submitted (#" + ticketId + ") in category " + template.category() + ".");
        panelLogger.log("TICKET", player.getName(), "Created ticket #" + ticketId + " [" + template.category() + "] " + template.description());
    }

    private ItemStack createTicketItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private record TicketTemplate(String category, String description) {
    }
}

