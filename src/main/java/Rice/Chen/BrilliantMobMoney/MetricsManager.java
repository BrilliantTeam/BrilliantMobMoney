package Rice.Chen.BrilliantMobMoney;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class MetricsManager {
    private final JavaPlugin plugin;
    private final File metricsFolder;
    private YamlConfiguration currentMetrics;
    private String currentDate;
    private final DateTimeFormatter dateFormatter;
    private final DateTimeFormatter timeFormatter;

    // 效能追蹤
    private final Queue<Long> processingTimes = new LinkedList<>();
    private final int MAX_SAMPLES = 600; // 保存最近600次處理的時間（約10分鐘的數據）
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private long lastUpdateTime = System.nanoTime();
    private double lastPluginMspt = 0;
    private double lastPluginTpsImpact = 0;

    public MetricsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.metricsFolder = new File(plugin.getDataFolder(), "metrics");
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        this.timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        
        if (!metricsFolder.exists()) {
            metricsFolder.mkdirs();
        }
        
        initializeCurrentDateFile();
    }
    
    private void initializeCurrentDateFile() {
        currentDate = LocalDate.now().format(dateFormatter);
        File metricsFile = new File(metricsFolder, currentDate + ".yml");
        currentMetrics = YamlConfiguration.loadConfiguration(metricsFile);
    }

    // 開始追蹤處理時間
    public long startTracking() {
        return System.nanoTime();
    }

    // 結束追蹤並記錄處理時間
    public void endTracking(long startTime) {
        long processingTime = System.nanoTime() - startTime;
        synchronized (processingTimes) {
            processingTimes.offer(processingTime);
            if (processingTimes.size() > MAX_SAMPLES) {
                processingTimes.poll();
            }
            totalProcessingTime.addAndGet(processingTime);
        }
    }

    // 計算插件的 MSPT 影響
    private double calculatePluginMspt() {
        synchronized (processingTimes) {
            if (processingTimes.isEmpty()) {
                return lastPluginMspt;
            }
            // 計算平均處理時間（轉換為毫秒）
            double avgProcessingTime = processingTimes.stream()
                .mapToDouble(time -> time / 1_000_000.0)
                .average()
                .orElse(0.0);
            
            lastPluginMspt = avgProcessingTime;
            return avgProcessingTime;
        }
    }

    // 計算插件對 TPS 的影響
    private double calculatePluginTpsImpact() {
        double pluginMspt = calculatePluginMspt();
        if (pluginMspt <= 0) {
            return lastPluginTpsImpact;
        }
        
        // 理想情況下每tick應該是50ms
        // 計算插件佔用的百分比，並轉換為TPS影響
        double tpsImpact = (pluginMspt / 50.0) * 20.0;
        lastPluginTpsImpact = tpsImpact;
        return tpsImpact;
    }
    
    // 獲取當前 MSPT 影響
    public double getCurrentMspt() {
        return calculatePluginMspt();
    }
    
    // 獲取當前 TPS 影響
    public double getCurrentTpsImpact() {
        return calculatePluginTpsImpact();
    }
    
    public void logMetrics(int processedCount, double rate, int cacheSize, int recentListSize) {
        // 檢查是否需要切換到新的日期檔案
        String today = LocalDate.now().format(dateFormatter);
        if (!today.equals(currentDate)) {
            initializeCurrentDateFile();
        }
        
        String timeKey = LocalDateTime.now().format(timeFormatter);
        double pluginMspt = calculatePluginMspt();
        double tpsImpact = calculatePluginTpsImpact();
        
        // 記錄插件效能影響
        currentMetrics.set(timeKey + ".插件效能影響", new String[] {
            String.format("MSPT：+%.3f", pluginMspt),
            String.format("TPS：-%.3f", tpsImpact),
            String.format("預估實際TPS：%.2f", 20.0 - tpsImpact)
        });
        
        // 記錄處理統計
        currentMetrics.set(timeKey + ".處理統計", new String[] {
            String.format("處理實體數量：%d", processedCount),
            String.format("處理速率：%.2f 個/秒", rate),
            String.format("緩存大小：%d", cacheSize),
            String.format("最近處理列表大小：%d", recentListSize),
            String.format("平均處理時間：+%.3f", processedCount > 0 ? pluginMspt / processedCount : 0)
        });
        
        try {
            currentMetrics.save(new File(metricsFolder, currentDate + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "無法儲存效能指標資料", e);
        }
    }
    
    public void cleanupOldMetrics(int daysToKeep) {
        LocalDate cutoffDate = LocalDate.now().minusDays(daysToKeep);
        
        File[] metricsFiles = metricsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (metricsFiles != null) {
            for (File file : metricsFiles) {
                String fileName = file.getName().replace(".yml", "");
                try {
                    LocalDate fileDate = LocalDate.parse(fileName, dateFormatter);
                    if (fileDate.isBefore(cutoffDate)) {
                        if (file.delete()) {
                            plugin.getLogger().info("已刪除舊的效能指標檔案：" + fileName);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("無法解析檔案日期：" + fileName);
                }
            }
        }
    }
}