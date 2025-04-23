package Rice.Chen.BrilliantMobMoney;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ConfigManager {
    private final JavaPlugin plugin;
    private final Map<String, String> entityNames = new HashMap<>();
    private final Map<String, MobConfig> mobConfigs = new ConcurrentHashMap<>();
    private FileConfiguration config;
    private FileConfiguration entityConfig;
    private File configFile;
    private File entityConfigFile;
    
    private int cleanupInterval;
    private int maxRecentEntries;
    private boolean enableMetrics;
    private int asyncTimeout;
    private boolean debug;
    private int metricsRetentionDays;
    private boolean showMessageInActionBar;
    private String actionBarMessage;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        saveDefaultConfigs();
        loadConfigs();
    }

    private void saveDefaultConfigs() {
        if (!new File(plugin.getDataFolder(), "config.yml").exists()) {
            plugin.saveResource("config.yml", false);
        }
        if (!new File(plugin.getDataFolder(), "entity.yml").exists()) {
            plugin.saveResource("entity.yml", false);
        }
    }

    public void loadConfigs() {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        config = YamlConfiguration.loadConfiguration(configFile);
        
        entityConfigFile = new File(plugin.getDataFolder(), "entity.yml");
        entityConfig = YamlConfiguration.loadConfiguration(entityConfigFile);

        loadEntityNames();
        
        loadSettings();
        
        loadMobConfigs();
    }

    private void loadSettings() {
        cleanupInterval = config.getInt("Settings.cleanup-interval", 30);
        maxRecentEntries = config.getInt("Settings.max-recent-entries", 1000);
        enableMetrics = config.getBoolean("Settings.enable-metrics", false);
        asyncTimeout = config.getInt("Settings.async-timeout", 5000);
        debug = config.getBoolean("Settings.debug", false);
        metricsRetentionDays = config.getInt("Settings.metrics-retention-days", 30);
        
        showMessageInActionBar = config.getBoolean("ShowMessageInActionBar.Enabled", true);
        actionBarMessage = config.getString("ShowMessageInActionBar.Message", 
            "§7｜§6系統§7｜§f飯娘：§7您擊殺了 §e%mob% §7，故獲得了 §a%amount%乙太§f（ꆙ） §7獎勵！");
    }

    private void loadEntityNames() {
        entityNames.clear();
        ConfigurationSection entityList = entityConfig.getConfigurationSection("EntityList");
        if (entityList != null) {
            for (String entityType : entityList.getKeys(false)) {
                String displayName = entityList.getString(entityType + ".DisplayName");
                if (displayName != null) {
                    entityNames.put(entityType, displayName);
                }
            }
        }
    }

    private void loadMobConfigs() {
        mobConfigs.clear();
        ConfigurationSection mobsSection = config.getConfigurationSection("Mobs");
        
        if (mobsSection != null) {
            for (String category : mobsSection.getKeys(false)) {
                ConfigurationSection categorySection = mobsSection.getConfigurationSection(category);
                if (categorySection != null) {
                    double defaultMin = categorySection.getDouble("Min", 0);
                    double defaultMax = categorySection.getDouble("Max", 0);
                    double defaultDropChance = categorySection.getDouble("DropChance", 0);
                    String defaultNumberOfDrops = categorySection.getString("NumberOfDrops", "1");
                    boolean defaultOnlyOnKill = categorySection.getBoolean("OnlyOnKill", true);
                    
                    for (String entityType : categorySection.getKeys(false)) {
                        if (entityType.equals("Min") || entityType.equals("Max") ||
                            entityType.equals("DropChance") || entityType.equals("NumberOfDrops") ||
                            entityType.equals("OnlyOnKill")) {
                            continue;
                        }
                        
                        ConfigurationSection mobSection = categorySection.getConfigurationSection(entityType);
                        if (mobSection != null) {
                            YamlConfiguration defaults = new YamlConfiguration();
                            defaults.set("Min", defaultMin);
                            defaults.set("Max", defaultMax);
                            defaults.set("DropChance", defaultDropChance);
                            defaults.set("NumberOfDrops", defaultNumberOfDrops);
                            defaults.set("OnlyOnKill", defaultOnlyOnKill);
                            
                            mobConfigs.put(entityType, new MobConfig(mobSection, entityType, this, defaults));
                        }
                    }
                }
            }
        }
        
        if (debug) {
            logMobConfigStats();
        }
    }

    private void logMobConfigStats() {
        plugin.getLogger().info("已載入 " + mobConfigs.size() + " 個生物配置。");
        ConfigurationSection mobsSection = config.getConfigurationSection("Mobs");
        if (mobsSection != null) {
            for (String category : mobsSection.getKeys(false)) {
                ConfigurationSection categorySection = mobsSection.getConfigurationSection(category);
                if (categorySection != null) {
                    long count = categorySection.getKeys(false).stream()
                        .filter(key -> !key.equals("Min") && !key.equals("Max") &&
                                     !key.equals("DropChance") && !key.equals("NumberOfDrops") &&
                                     !key.equals("OnlyOnKill"))
                        .count();
                    plugin.getLogger().info(String.format("- %s：%d 個配置", category, count));
                }
            }
        }
    }

    public int getCleanupInterval() { return cleanupInterval; }
    public int getMaxRecentEntries() { return maxRecentEntries; }
    public boolean isEnableMetrics() { return enableMetrics; }
    public int getAsyncTimeout() { return asyncTimeout; }
    public boolean isDebug() { return debug; }
    public int getMetricsRetentionDays() { return metricsRetentionDays; }
    public boolean isShowMessageInActionBar() { return showMessageInActionBar; }
    public String getActionBarMessage() { return actionBarMessage; }
    public Map<String, MobConfig> getMobConfigs() { return mobConfigs; }
    
    public String getEntityDisplayName(String entityType) {
        return entityNames.getOrDefault(entityType, entityType);
    }

    public static class MobConfig {
        public final boolean enabled;
        public final double min;
        public final double max;
        public final double dropChance;
        public final String numberOfDrops;
        public final boolean onlyOnKill;
        public final String displayName;

        MobConfig(ConfigurationSection section, String entityType, ConfigManager configManager,
                 ConfigurationSection defaults) {
            this.enabled = section.getBoolean("Enabled", true);
            this.min = section.getDouble("Min", defaults.getDouble("Min", 0));
            this.max = section.getDouble("Max", defaults.getDouble("Max", 0));
            this.dropChance = section.getDouble("DropChance", defaults.getDouble("DropChance", 0));
            this.numberOfDrops = section.getString("NumberOfDrops", 
                defaults.getString("NumberOfDrops", "1"));
            this.onlyOnKill = section.getBoolean("OnlyOnKill", 
                defaults.getBoolean("OnlyOnKill", true));
            this.displayName = configManager.getEntityDisplayName(entityType);
        }
    }
}