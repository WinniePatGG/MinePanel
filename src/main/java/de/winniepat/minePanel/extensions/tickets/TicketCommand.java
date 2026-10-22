package de.winniepat.minePanel.extensions.tickets;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TicketCommand implements CommandExecutor {

    private final TicketMenuListener menuListener;

    public TicketCommand(TicketMenuListener menuListener) {
        this.menuListener = menuListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can create support tickets.");
            return true;
        }

        menuListener.openMenu(player);
        return true;
    }
}

