package Rice.Chen.BrilliantMobMoney;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;

public class BrilliantMobMoney extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private final Random random = new Random();
    private ConfigManager configManager;
    private MetricsManager metricsManager;
    
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private long lastMetricsTime = System.currentTimeMillis();
    
    private final Set<UUID> recentlyProcessed = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private boolean isFolia;
    
    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onEnable() {
        this.isFolia = isFolia();
        configManager = new ConfigManager(this);
        
        if (configManager.isEnableMetrics()) {
            metricsManager = new MetricsManager(this);
        }
        
        if (!setupEconomy()) {
            getLogger().severe("未找到 Vault 插件！正在禁用插件...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("bmm").setExecutor(this);
        getCommand("bmm").setTabCompleter(this);
        
        setupScheduledTasks();
        
        getLogger().info("BrilliantMobMoney 插件已啟用！");
    }

    
    private void setupScheduledTasks() {
        if (isFolia) {
            GlobalRegionScheduler globalScheduler = getServer().getGlobalRegionScheduler();
            AsyncScheduler asyncScheduler = getServer().getAsyncScheduler();
            
            globalScheduler.runAtFixedRate(this, (task) -> {
                if (recentlyProcessed.size() > configManager.getMaxRecentEntries()) {
                    recentlyProcessed.clear();
                    if (configManager.isDebug()) {
                        getLogger().info("已清理最近處理的實體緩存");
                    }
                }
                
                if (configManager.isEnableMetrics()) {
                    metricsManager.cleanupOldMetrics(configManager.getMetricsRetentionDays());
                }
            }, 1, configManager.getCleanupInterval());
            
            if (configManager.isEnableMetrics()) {
                globalScheduler.runAtFixedRate(this, (task) -> this.logMetrics(), 300, 300);
            }
        } else {
            getServer().getScheduler().runTaskTimer(this, () -> {
                if (recentlyProcessed.size() > configManager.getMaxRecentEntries()) {
                    recentlyProcessed.clear();
                    if (configManager.isDebug()) {
                        getLogger().info("已清理最近處理的實體緩存");
                    }
                }
                
                if (configManager.isEnableMetrics()) {
                    metricsManager.cleanupOldMetrics(configManager.getMetricsRetentionDays());
                }
            }, configManager.getCleanupInterval() * 20L, configManager.getCleanupInterval() * 20L);
            
            if (configManager.isEnableMetrics()) {
                getServer().getScheduler().runTaskTimer(this, this::logMetrics, 6000L, 6000L);
            }
        }
    }
    
    private void logMetrics() {
        long now = System.currentTimeMillis();
        int count = processedCount.getAndSet(0);
        double timeSpan = (now - lastMetricsTime) / 1000.0;
        double rate = count / timeSpan;
        
        if (configManager.isEnableMetrics()) {
            metricsManager.logMetrics(count, rate, configManager.getMobConfigs().size(), recentlyProcessed.size());
        }
        
        getLogger().info(String.format(
            "效能監控：已處理 %d 個實體 (%.2f個/秒) | 緩存大小：%d | 最近處理列表大小：%d",
            count, rate, configManager.getMobConfigs().size(), recentlyProcessed.size()
        ));
        
        lastMetricsTime = now;
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
            .legacySection()
            .deserialize(message));
    }

    private void sendMessages(CommandSender sender, String[] messages) {
        for (String message : messages) {
            sendMessage(sender, message);
        }
    }

    @Override
    public void onDisable() {
        recentlyProcessed.clear();
        getLogger().info("BrilliantMobMoney 插件已禁用！");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("brilliantmobmoney.reload")) {
                    sendMessage(sender, "§7｜§6系統§7｜§f飯娘：§7您沒有權限執行此指令！");
                    return true;
                }
                handleReload(sender);
                break;

            case "metrics":
                if (!sender.hasPermission("brilliantmobmoney.metrics")) {
                    sendMessage(sender, "§7｜§6系統§7｜§f飯娘：§7您沒有權限執行此指令！");
                    return true;
                }
                handleMetrics(sender, args);
                break;

            default:
                showHelp(sender);
                break;
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        try {
            configManager.loadConfigs();
            sendMessage(sender, "§7｜§6系統§7｜§f飯娘：§7已重新載入設定檔案完成！");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "重新載入配置時發生錯誤", e);
            sendMessage(sender, "§7｜§6系統§7｜§f飯娘：§7設定檔重新載入失敗，請查看控制台獲取詳細信息！");
        }
    }

    private void handleMetrics(CommandSender sender, String[] args) {
        if (!configManager.isEnableMetrics()) {
            sendMessage(sender, "§7｜§6系統§7｜§f飯娘：§7效能監控功能目前已停用！");
            return;
        }

        if (args.length < 2) {
            showMetricsHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "record":
                long now = System.currentTimeMillis();
                int count = processedCount.getAndSet(0);
                double timeSpan = (now - lastMetricsTime) / 1000.0;
                double rate = count / timeSpan;
                
                metricsManager.logMetrics(count, rate, configManager.getMobConfigs().size(), recentlyProcessed.size());
                lastMetricsTime = now;
                
                sendMessage(sender, "§7｜§6系統§7｜§f飯娘：§7已手動記錄當前效能指標！");
                break;

            case "status":
                showCurrentMetrics(sender);
                break;

            default:
                showMetricsHelp(sender);
                break;
        }
    }

    private void showCurrentMetrics(CommandSender sender) {
        int count = processedCount.get();
        double timeSpan = (System.currentTimeMillis() - lastMetricsTime) / 1000.0;
        double rate = timeSpan > 0 ? count / timeSpan : 0;

        sendMessages(sender, new String[] {
            "§6==========[效能監控]==========",
            String.format("§f處理實體數量：§e%d", count),
            String.format("§f處理速率：§e%.2f 個/秒", rate),
            String.format("§f緩存大小：§e%d", configManager.getMobConfigs().size()),
            String.format("§f最近處理列表大小：§e%d", recentlyProcessed.size()),
            String.format("§f插件MSPT影響：§e+%.3f", metricsManager.getCurrentMspt()),
            String.format("§f插件TPS影響：§e-%.3f", metricsManager.getCurrentTpsImpact()),
            "§6=============================="
        });
    }

    private void showMetricsHelp(CommandSender sender) {
        sendMessages(sender, new String[] {
            "§6==========[效能監控]==========",
            "§e/bmm metrics record §7- §f手動記錄當前效能",
            "§e/bmm metrics status §7- §f查看當前效能狀態",
            "§6=============================="
        });
    }

    private void showHelp(CommandSender sender) {
        sendMessages(sender, new String[] {
            "§6==========[ BrilliantMobMoney ]==========",
            "§e/bmm reload §7- §f重新載入配置文件",
            "§e/bmm metrics §7- §f效能監控相關指令",
            "§6====================================="
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            if (sender.hasPermission("brilliantmobmoney.reload")) {
                completions.add("reload");
            }
            if (sender.hasPermission("brilliantmobmoney.metrics")) {
                completions.add("metrics");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("metrics")) {
            if (sender.hasPermission("brilliantmobmoney.metrics")) {
                completions.add("record");
                completions.add("status");
            }
        }
        
        return completions.stream()
            .filter(completion -> completion.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
            .collect(Collectors.toList());
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private double calculateReward(ConfigManager.MobConfig config) {
        if (config.min == config.max) {
            return config.min;
        }
        return config.min + (config.max - config.min) * random.nextDouble();
    }

    private int calculateDrops(String dropRange) {
        if (dropRange.contains("-")) {
            String[] range = dropRange.split("-");
            int minDrops = Integer.parseInt(range[0]);
            int maxDrops = Integer.parseInt(range[1]);
            return random.nextInt(maxDrops - minDrops + 1) + minDrops;
        }
        return Integer.parseInt(dropRange);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        
        if (killer == null) return;
        
        String entityType = entity.getType().name();
        ConfigManager.MobConfig mobConfig = configManager.getMobConfigs().get(entityType);
        
        if (!recentlyProcessed.add(entity.getUniqueId())) {
            if (configManager.isDebug()) {
                getLogger().info("跳過重複的實體：" + entityType);
            }
            return;
        }
        
        if (mobConfig == null || !mobConfig.enabled) {
            return;
        }
        
        if (mobConfig.onlyOnKill && killer == null) {
            return;
        }
        
        double randomChance = random.nextDouble() * 100.0;
        if (randomChance > mobConfig.dropChance) {
            if (configManager.isDebug()) {
                getLogger().info(String.format(
                    "未通過機率檢查：實體=%s, 隨機數=%.2f, 設定機率=%.2f",
                    entityType, randomChance, mobConfig.dropChance));
            }
            return;
        }
        
        final long startTime = configManager.isEnableMetrics() ? metricsManager.startTracking() : 0;
        double reward = calculateReward(mobConfig);
        int drops = calculateDrops(mobConfig.numberOfDrops);
        final double totalReward = reward * drops;
        
        if (totalReward <= 0) {
            if (configManager.isDebug()) {
                getLogger().info("獎勵金額為零或負數，跳過處理");
            }
            return;
        }

        if (configManager.isDebug()) {
            getLogger().info(String.format(
                "準備給予獎勵：玩家=%s, 實體=%s, 獎勵=%f, 通過機率檢查：%.2f <= %.2f", 
                killer.getName(), entityType, totalReward, randomChance, mobConfig.dropChance));
        }
r
        final Player finalKiller = killer;
        if (isFolia) {
            getServer().getGlobalRegionScheduler().execute(this, () -> {
                try {
                    boolean success = economy.depositPlayer(finalKiller, totalReward).transactionSuccess();
                    
                    if (success) {
                        final net.kyori.adventure.text.Component component;
                        if (configManager.isShowMessageInActionBar()) {
                            String message = configManager.getActionBarMessage()
                                .replace("%mob%", mobConfig.displayName)
                                .replace("%amount%", String.format("%.2f", totalReward));
                            
                            component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection()
                                .deserialize(message);
                            
                            finalKiller.sendActionBar(component);
                        } else {
                            String message = String.format("§7｜§6系統§7｜§f飯娘：§7您擊殺了 §e%s §7，故獲得了 §a%.2f乙太§f（ꆙ） §7獎勵！", 
                                mobConfig.displayName, totalReward);
                            
                            component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection()
                                .deserialize(message);
                            
                            finalKiller.sendMessage(component);
                        }

                        if (configManager.isEnableMetrics()) {
                            processedCount.incrementAndGet();
                            metricsManager.endTracking(startTime);
                        }
                    } else {
                        getLogger().warning(String.format("經濟操作失敗：玩家=%s, 金額=%f", 
                            finalKiller.getName(), totalReward));
                    }
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, String.format(
                        "處理經濟操作時發生錯誤：玩家=%s, 金額=%f", finalKiller.getName(), totalReward), e);
                    e.printStackTrace();
                }
            });
        } else {
            getServer().getScheduler().runTask(this, () -> {
                try {
                    boolean success = economy.depositPlayer(finalKiller, totalReward).transactionSuccess();
                    if (success) {
                        if (configManager.isShowMessageInActionBar()) {
                            String message = configManager.getActionBarMessage()
                                .replace("%mob%", mobConfig.displayName)
                                .replace("%amount%", String.format("%.2f", totalReward));
                            
                            finalKiller.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection()
                                .deserialize(message));
                        } else {
                            String message = String.format("§7｜§6系統§7｜§f飯娘：§7您擊殺了 §e%s §7，故獲得了 §a%.2f乙太§f（ꆙ） §7獎勵！", 
                                mobConfig.displayName, totalReward);
                            
                            finalKiller.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection()
                                .deserialize(message));
                        }
                        
                        if (configManager.isEnableMetrics()) {
                            processedCount.incrementAndGet();
                            metricsManager.endTracking(startTime);
                        }
                    } else {
                        getLogger().warning(String.format("經濟操作失敗：玩家=%s, 金額=%f", 
                            finalKiller.getName(), totalReward));
                    }
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "處理事件時發生錯誤", e);
                }
            });
        }
    }
}