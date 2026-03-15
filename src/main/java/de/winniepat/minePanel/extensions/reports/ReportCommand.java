package de.winniepat.minePanel.extensions.reports;

import de.winniepat.minePanel.logs.PanelLogger;
import de.winniepat.minePanel.persistence.KnownPlayerRepository;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Arrays;

public final class ReportCommand implements CommandExecutor {

    private final ReportRepository reportRepository;
    private final KnownPlayerRepository knownPlayerRepository;
    private final PanelLogger panelLogger;

    public ReportCommand(ReportRepository reportRepository, KnownPlayerRepository knownPlayerRepository, PanelLogger panelLogger) {
        this.reportRepository = reportRepository;
        this.knownPlayerRepository = knownPlayerRepository;
        this.panelLogger = panelLogger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage(ChatColor.RED + "Only players can create reports.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /report <player> <reason>");
            return true;
        }

        Player suspect = reporter.getServer().getPlayerExact(args[0]);
        if (suspect == null) {
            sender.sendMessage(ChatColor.RED + "The reported player must currently be online.");
            return true;
        }

        if (suspect.getUniqueId().equals(reporter.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You cannot report yourself.");
            return true;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (reason.isBlank()) {
            sender.sendMessage(ChatColor.RED + "Please provide a reason.");
            return true;
        }

        long now = Instant.now().toEpochMilli();
        knownPlayerRepository.upsert(reporter.getUniqueId(), reporter.getName(), now);
        knownPlayerRepository.upsert(suspect.getUniqueId(), suspect.getName(), now);

        long reportId = reportRepository.createReport(
                reporter.getUniqueId(),
                reporter.getName(),
                suspect.getUniqueId(),
                suspect.getName(),
                reason,
                now
        );

        sender.sendMessage(ChatColor.GREEN + "Report submitted (#" + reportId + ") for " + suspect.getName() + ".");
        panelLogger.log("REPORT", reporter.getName(), "Created report #" + reportId + " against " + suspect.getName() + ": " + reason);
        return true;
    }
}

