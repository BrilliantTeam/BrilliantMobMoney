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

public class BrilliantMobMoney extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private final Random random = new Random();
    private ConfigManager configManager;
    private MetricsManager metricsManager;
    
    // 效能監控
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private long lastMetricsTime = System.currentTimeMillis();
    
    // 配置緩存
    private final Set<UUID> recentlyProcessed = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        // 初始化配置管理器
        configManager = new ConfigManager(this);
        
        // 初始化 MetricsManager
        if (configManager.isEnableMetrics()) {
            metricsManager = new MetricsManager(this);
        }
        
        // 設置 Vault
        if (!setupEconomy()) {
            getLogger().severe("未找到 Vault 插件！正在禁用插件...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // 註冊事件和命令
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("bmm").setExecutor(this);
        getCommand("bmm").setTabCompleter(this);
        
        // 設置定時任務
        setupScheduledTasks();
        
        getLogger().info("BrilliantMobMoney 插件已啟用！");
    }
    
    private void setupScheduledTasks() {
        // 清理任務
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (recentlyProcessed.size() > configManager.getMaxRecentEntries()) {
                recentlyProcessed.clear();
                if (configManager.isDebug()) {
                    getLogger().info("已清理最近處理的實體緩存");
                }
            }
            
            // 清理舊的效能指標檔案
            if (configManager.isEnableMetrics()) {
                metricsManager.cleanupOldMetrics(configManager.getMetricsRetentionDays());
            }
        }, configManager.getCleanupInterval() * 20L, configManager.getCleanupInterval() * 20L);
        
        // 效能監控任務
        if (configManager.isEnableMetrics()) {
            getServer().getScheduler().runTaskTimer(this, this::logMetrics, 6000L, 6000L);
        }
    }
    
    private void logMetrics() {
        long now = System.currentTimeMillis();
        int count = processedCount.getAndSet(0);
        double timeSpan = (now - lastMetricsTime) / 1000.0;
        double rate = count / timeSpan;
        
        // 記錄到檔案
        if (configManager.isEnableMetrics()) {
            metricsManager.logMetrics(count, rate, configManager.getMobConfigs().size(), recentlyProcessed.size());
        }
        
        // 同時也在控制台顯示
        getLogger().info(String.format(
            "效能監控：已處理 %d 個實體 (%.2f個/秒) | 緩存大小：%d | 最近處理列表大小：%d",
            count, rate, configManager.getMobConfigs().size(), recentlyProcessed.size()
        ));
        
        lastMetricsTime = now;
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
                    sender.sendMessage("§7｜§6系統§7｜§f飯娘：§7您沒有權限執行此指令！");
                    return true;
                }
                handleReload(sender);
                break;

            case "metrics":
                if (!sender.hasPermission("brilliantmobmoney.metrics")) {
                    sender.sendMessage("§7｜§6系統§7｜§f飯娘：§7您沒有權限執行此指令！");
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
            sender.sendMessage("§7｜§6系統§7｜§f飯娘：§7已重新載入設定檔案完成！");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "重新載入配置時發生錯誤", e);
            sender.sendMessage("§7｜§6系統§7｜§f飯娘：§7設定檔重新載入失敗，請查看控制台獲取詳細信息！");
        }
    }

    private void handleMetrics(CommandSender sender, String[] args) {
        if (!configManager.isEnableMetrics()) {
            sender.sendMessage("§7｜§6系統§7｜§f飯娘：§7效能監控功能目前已停用！");
            return;
        }

        if (args.length < 2) {
            showMetricsHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "record":
                // 強制記錄當前效能
                long now = System.currentTimeMillis();
                int count = processedCount.getAndSet(0);
                double timeSpan = (now - lastMetricsTime) / 1000.0;
                double rate = count / timeSpan;
                
                metricsManager.logMetrics(count, rate, configManager.getMobConfigs().size(), recentlyProcessed.size());
                lastMetricsTime = now;
                
                sender.sendMessage("§7｜§6系統§7｜§f飯娘：§7已手動記錄當前效能指標！");
                break;

            case "status":
                // 顯示當前效能狀態
                showCurrentMetrics(sender);
                break;

            default:
                showMetricsHelp(sender);
                break;
        }
    }

    private void showCurrentMetrics(CommandSender sender) {
        // 獲取當前效能數據
        int count = processedCount.get();
        double timeSpan = (System.currentTimeMillis() - lastMetricsTime) / 1000.0;
        double rate = timeSpan > 0 ? count / timeSpan : 0;

        sender.sendMessage(new String[] {
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
        sender.sendMessage(new String[] {
            "§6==========[效能監控]==========",
            "§e/bmm metrics record §7- §f手動記錄當前效能",
            "§e/bmm metrics status §7- §f查看當前效能狀態",
            "§6=============================="
        });
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(new String[] {
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
        
        if (!recentlyProcessed.add(entity.getUniqueId())) {
            if (configManager.isDebug()) {
                getLogger().info("跳過重複的實體：" + entity.getType());
            }
            return;
        }
        
        // 開始追蹤處理時間
        final long startTime = configManager.isEnableMetrics() ? metricsManager.startTracking() : 0;
        
        CompletableFuture.runAsync(() -> {
            try {
                processEntityDeath(entity);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "處理實體死亡時發生錯誤", e);
            } finally {
                // 結束追蹤處理時間
                if (configManager.isEnableMetrics()) {
                    metricsManager.endTracking(startTime);
                }
            }
        }).orTimeout(configManager.getAsyncTimeout(), TimeUnit.MILLISECONDS)
          .exceptionally(throwable -> {
              if (configManager.isDebug()) {
                  getLogger().warning("異步處理超時或失敗，實體類型：" + entity.getType());
              }
              return null;
          });
    }
    
    private void processEntityDeath(LivingEntity entity) {
        Player killer = entity.getKiller();
        String entityType = entity.getType().name();
        
        ConfigManager.MobConfig mobConfig = configManager.getMobConfigs().get(entityType);
        if (mobConfig == null || !mobConfig.enabled) {
            return;
        }
        
        if (mobConfig.onlyOnKill && killer == null) {
            return;
        }
        
        if (random.nextDouble() * 100 > mobConfig.dropChance) {
            return;
        }
        
        double reward = calculateReward(mobConfig);
        int drops = calculateDrops(mobConfig.numberOfDrops);
        final double totalReward = reward * drops;
        
        if (killer != null && totalReward > 0) {
            getServer().getScheduler().runTask(this, () -> {
                economy.depositPlayer(killer, totalReward);
                
                if (configManager.isShowMessageInActionBar()) {
                    // 使用 ActionBar 發送訊息
                    String message = configManager.getActionBarMessage()
                        .replace("%mob%", mobConfig.displayName)
                        .replace("%amount%", String.format("%.2f", totalReward));
                    killer.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));
                } else {
                    // 使用傳統聊天訊息
                    String message = String.format("§7｜§6系統§7｜§f飯娘：§7您擊殺了 §e%s §7，故獲得了 §a%.2f乙太§f（ꆙ） §7獎勵！", 
                        mobConfig.displayName, totalReward);
                    killer.sendMessage(message);
                }
                
                if (configManager.isEnableMetrics()) {
                    processedCount.incrementAndGet();
                }
            });
        }
    }
}