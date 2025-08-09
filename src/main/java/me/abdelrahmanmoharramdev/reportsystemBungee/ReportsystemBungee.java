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
    private final long cooldownTime = 10 * 1000; // 10 seconds
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

            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /report <player> <reason>");
                player.sendMessage(ChatColor.YELLOW + "Reasons: " + String.join(", ", reportReasons));
                return;
            }

            String reported = args[0];
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

            cooldowns.put(uuid, now);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

            player.sendMessage(ChatColor.GREEN + "✅ Report submitted.");

            Report report = new Report(time, player.getName(), reported, reason);
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
                    TextComponent base = new TextComponent(ChatColor.YELLOW + r.reporter + ChatColor.GRAY + " ➜ " + ChatColor.RED + r.reported);
                    base.addExtra(ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + r.reason);

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
                + "{\"name\": \"📝 Reason\", \"value\": \"" + report.reason + "\"}"
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
            writer.write(String.format("[%s] %s reported %s for: %s%n", report.time, report.reporter, report.reported, report.reason));
        } catch (Exception e) {
            getLogger().warning("Failed to write to reports.log");
            e.printStackTrace();
        }
    }

    private void notifyModerators(Report report) {
        String msg = ChatColor.RED + "[ReportSystem] " + ChatColor.YELLOW +
                report.reporter + " reported " + report.reported + " for: " + ChatColor.GRAY + report.reason;

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

        public Report(String time, String reporter, String reported, String reason) {
            this.time = time;
            this.reporter = reporter;
            this.reported = reported;
            this.reason = reason;
        }
    }
}
