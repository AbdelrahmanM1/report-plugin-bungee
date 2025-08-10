package me.abdelrahmanmoharramdev.reportsystemBungee;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportsystemBungee extends Plugin implements Listener {

    private Configuration config;
    private String webhookUrl;
    private List<String> reportReasons;
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final long cooldownTime = 10 * 1000; // 10 seconds cooldown
    private final List<Report> reports = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEnable() {
        loadConfig();
        getProxy().getPluginManager().registerCommand(this, new ReportCommand());
        getProxy().getPluginManager().registerCommand(this, new ReportReloadCommand());
        getProxy().getPluginManager().registerCommand(this, new ReportListCommand());
        getLogger().info("✅ ReportSystemBungee Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("❌ ReportSystemBungee Disabled");
    }

    private void loadConfig() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdir();

            File file = new File(getDataFolder(), "config.yml");
            if (!file.exists()) {
                getLogger().info("Creating default config.yml...");
                file.createNewFile();
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write("webhook-url: \"https://discord.com/api/webhooks/YOUR_WEBHOOK_ID\"\n");
                    writer.write("report-reasons:\n");
                    writer.write("  - Cheating\n");
                    writer.write("  - Abusive Language\n");
                    writer.write("  - Griefing\n");
                    writer.write("  - Spamming\n");
                    writer.write("  - Inappropriate Skin/Name\n");
                }
            }

            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
            webhookUrl = config.getString("webhook-url", "");
            reportReasons = config.getStringList("report-reasons");

            if (reportReasons.isEmpty()) {
                reportReasons.add("Cheating");
                reportReasons.add("Abusive Language");
                reportReasons.add("Griefing");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveConfig() {
        try {
            File file = new File(getDataFolder(), "config.yml");
            ConfigurationProvider.getProvider(YamlConfiguration.class).save(config, file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class ReportCommand extends Command {
        public ReportCommand() {
            super("report", "reportsystem.report");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!(sender instanceof ProxiedPlayer player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return;
            }

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();

            if (cooldowns.containsKey(uuid) && now - cooldowns.get(uuid) < cooldownTime) {
                long secondsLeft = (cooldownTime - (now - cooldowns.get(uuid))) / 1000;
                player.sendMessage(ChatColor.RED + "⏳ Please wait " + secondsLeft + " seconds before reporting again.");
                return;
            }

            // Case 1: No args - show clickable list of online players except self
            if (args.length == 0) {
                player.sendMessage(ChatColor.GOLD + "Click a player to report:");
                for (ProxiedPlayer target : getProxy().getPlayers()) {
                    if (target.equals(player)) continue; // skip self-reporting
                    TextComponent playerComp = new TextComponent(ChatColor.AQUA + target.getName() + " ");
                    playerComp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/report " + target.getName()));
                    playerComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Report " + target.getName()).create()));
                    player.sendMessage(playerComp);
                }
                return;
            }

            // Case 2: One arg (player name) - show clickable reasons for that player
            if (args.length == 1) {
                String targetName = args[0];

                ProxiedPlayer targetPlayer = getProxy().getPlayer(targetName);
                if (targetPlayer == null) {
                    player.sendMessage(ChatColor.RED + "Player " + targetName + " not found.");
                    return;
                }

                player.sendMessage(ChatColor.GOLD + "Click a reason to report " + ChatColor.AQUA + targetName + ChatColor.GOLD + ":");
                for (String reason : reportReasons) {
                    TextComponent reasonComp = new TextComponent(ChatColor.GREEN + "[" + reason + "] ");
                    reasonComp.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/report " + targetName + " " + reason));
                    reasonComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Report " + targetName + " for " + reason).create()));
                    player.sendMessage(reasonComp);
                }
                return;
            }

            // Case 3: args.length >= 2 - submit the report
            String reported = args[0];
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

            // Validate reported player online or allow offline? Up to you. Let's allow offline here.
            // Optionally you could do:
            // ProxiedPlayer reportedPlayer = getProxy().getPlayer(reported);
            // if (reportedPlayer == null) { player.sendMessage(ChatColor.RED + "Player not found."); return; }

            cooldowns.put(uuid, now);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String serverName = player.getServer() != null ? player.getServer().getInfo().getName() : "Unknown";

            player.sendMessage(ChatColor.GREEN + "✅ Report submitted for " + ChatColor.RED + reported + ChatColor.GREEN + ".");

            Report report = new Report(time, player.getName(), reported, reason, serverName);
            reports.add(report);
            sendToWebhook(report);
            logToFile(report);
            notifyModerators(report);
        }
    }

    private class ReportReloadCommand extends Command {
        public ReportReloadCommand() {
            super("reportreload", "reportsystem.reload");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            loadConfig();
            sender.sendMessage(ChatColor.GREEN + "✅ ReportSystem config reloaded.");
        }
    }

    private class ReportListCommand extends Command {
        public ReportListCommand() {
            super("reportlist", "reportsystem.list");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!(sender instanceof ProxiedPlayer player)) {
                sender.sendMessage(ChatColor.RED + "Only players can view reports.");
                return;
            }
            if (!player.hasPermission("reportsystem.list")) {
                player.sendMessage(ChatColor.RED + "You do not have permission to view reports.");
                return;
            }

            player.sendMessage(ChatColor.GOLD + "=== Recent Reports ===");
            synchronized (reports) {
                if (reports.isEmpty()) {
                    player.sendMessage(ChatColor.GRAY + "No reports found.");
                    return;
                }
                for (Report r : reports) {
                    TextComponent base = new TextComponent(
                            ChatColor.YELLOW + r.reporter +
                                    ChatColor.GRAY + " ➜ " +
                                    ChatColor.RED + r.reported +
                                    ChatColor.DARK_GRAY + " | " +
                                    ChatColor.GRAY + r.reason +
                                    ChatColor.DARK_GRAY + " | " +
                                    ChatColor.BLUE + "Server: " + ChatColor.AQUA + r.server
                    );

                    TextComponent tpBtn = new TextComponent(ChatColor.GREEN + " [Action]");
                    tpBtn.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + r.reporter + " Regarding your report"));
                    tpBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Contact reporter").create()));

                    base.addExtra(tpBtn);
                    player.sendMessage(base);
                }
            }
        }
    }

    private void sendToWebhook(Report report) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String json = "{"
                + "\"embeds\": [{"
                + "\"title\": \"🚨 New Report\","
                + "\"color\": 15158332,"
                + "\"fields\": ["
                + "{\"name\": \"📅 Time\", \"value\": \"" + report.time + "\", \"inline\": true},"
                + "{\"name\": \"👤 Reporter\", \"value\": \"" + report.reporter + "\", \"inline\": true},"
                + "{\"name\": \"🔴 Reported\", \"value\": \"" + report.reported + "\", \"inline\": true},"
                + "{\"name\": \"📝 Reason\", \"value\": \"" + report.reason + "\"},"
                + "{\"name\": \"🖥️ Server\", \"value\": \"" + report.server + "\", \"inline\": true}"
                + "]"
                + "}]"
                + "}";

        getProxy().getScheduler().runAsync(this, () -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(json.getBytes());
                    os.flush();
                }

                connection.getResponseCode();
                connection.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to send to Discord webhook.");
                e.printStackTrace();
            }
        });
    }

    private void logToFile(Report report) {
        try (FileWriter writer = new FileWriter(getDataFolder() + "/reports.log", true)) {
            writer.write(String.format("[%s] [%s] %s reported %s for: %s%n", report.time, report.server, report.reporter, report.reported, report.reason));
        } catch (Exception e) {
            getLogger().warning("Failed to write to reports.log");
            e.printStackTrace();
        }
    }

    private void notifyModerators(Report report) {
        String msg = ChatColor.RED + "[ReportSystem] " + ChatColor.YELLOW +
                report.reporter + " reported " + report.reported +
                ChatColor.GRAY + " for: " + report.reason +
                ChatColor.BLUE + " [Server: " + ChatColor.AQUA + report.server + ChatColor.BLUE + "]";

        for (ProxiedPlayer p : getProxy().getPlayers()) {
            if (p.hasPermission("reportsystem.notify")) {
                p.sendMessage(msg);
            }
        }
    }

    private static class Report {
        String time;
        String reporter;
        String reported;
        String reason;
        String server;

        public Report(String time, String reporter, String reported, String reason, String server) {
            this.time = time;
            this.reporter = reporter;
            this.reported = reported;
            this.reason = reason;
            this.server = server;
        }
    }
}
