package com.hazelcast.util;

import com.hazelcast.cluster.ClusterService;
import com.hazelcast.impl.ExecutorManager;
import com.hazelcast.impl.Node;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static java.lang.String.format;

//http://blog.scoutapp.com/articles/2009/07/31/understanding-load-averages
//http://docs.oracle.com/javase/7/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html
public class HealthMonitor extends Thread {
    private final ILogger logger;
    private final Node node;
    private final Runtime runtime;
    private final OperatingSystemMXBean osMxBean;
    private final HealthMonitorLevel logLevel;
    private final int delaySeconds;
    private final ThreadMXBean threadMxBean;
    private double threshold = 70;

    public HealthMonitor(Node node, HealthMonitorLevel logLevel, int delaySeconds) {
        super(node.threadGroup, node.getThreadNamePrefix("HealthMonitor"));
        setDaemon(true);
        this.delaySeconds = delaySeconds;
        this.node = node;
        this.logger = node.getLogger(HealthMonitor.class.getName());
        this.runtime = Runtime.getRuntime();
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.logLevel = logLevel;
        threadMxBean = ManagementFactory.getThreadMXBean();
    }

    public void run() {
        if (logLevel == HealthMonitorLevel.OFF) {
            return;
        }

        while (node.isActive()) {
            HealthMetrics metrics;
            switch (logLevel) {
                case NOISY:
                    metrics = new HealthMetrics();
                    logger.log(Level.INFO, metrics.toString());
                    break;
                case SILENT:
                    metrics = new HealthMetrics();
                    if (metrics.exceedsThreshold()) {
                        logger.log(Level.INFO, metrics.toString());
                    }
                    break;
                default:
                    throw new IllegalStateException("unrecognized logLevel:" + logLevel);
            }

            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(delaySeconds));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public class HealthMetrics {
        final long memoryFree;
        final long memoryTotal;
        final long memoryUsed;
        final long memoryMax;
        final double memoryUsedOfTotalPercentage;
        final double memoryUsedOfMaxPercentage;
        //following three load variables are always between 0 and 100.
        final double processCpuLoad;
        final double systemLoadAverage;
        final double systemCpuLoad;

        final int packetQueueSize;
        final int processableQueueSize;
        final int processablePriorityQueueSize;
        final int threadCount;
        final int peakThreadCount;

        final int queryQueueSize;
        final int mapLoaderExecutorQueueSize;
        final int defaultExecutorQueueSize;
        final int asyncExecutorQueueSize;
        final int eventExecutorQueueSize;
        final int mapStoreExecutorQueueSize;
        final Map<Address, Integer> outputQueueSizes = new HashMap<Address, Integer>();
        final int maxOutputQueueSize;

        public HealthMetrics() {
            memoryFree = runtime.freeMemory();
            memoryTotal = runtime.totalMemory();
            memoryUsed = memoryTotal - memoryFree;
            memoryMax = runtime.maxMemory();
            memoryUsedOfTotalPercentage = 100d * memoryUsed / memoryTotal;
            memoryUsedOfMaxPercentage = 100d * memoryUsed / memoryMax;
            processCpuLoad = get(osMxBean, "getProcessCpuLoad", -1L);
            systemLoadAverage = get(osMxBean, "getSystemLoadAverage", -1L);
            systemCpuLoad = get(osMxBean, "getSystemCpuLoad", -1L);


            ClusterService clusterService = node.getClusterService();
            final Map<Address, Connection> readonlyConnectionMap = node.getConnectionManager().getReadonlyConnectionMap();
            for (Connection connection : readonlyConnectionMap.values()) {
                final int size = connection.getWriteHandler().size();
                final Address address = connection.getEndPoint();
                outputQueueSizes.put(address, size);
            }

            maxOutputQueueSize = node.getGroupProperties().MAX_CONNECTION_OUTPUT_QUEUE_SIZE.getInteger();

            this.packetQueueSize = clusterService.getPacketQueueSize();
            this.processableQueueSize = clusterService.getProcessableQueueSize();
            this.processablePriorityQueueSize = clusterService.getProcessablePriorityQueueSize();

            this.threadCount = threadMxBean.getThreadCount();
            this.peakThreadCount = threadMxBean.getPeakThreadCount();

            ExecutorManager.Statistics statistics = node.getExecutorManager().getStatistics();
            this.queryQueueSize = statistics.queryQueueSize;
            this.mapLoaderExecutorQueueSize = statistics.mapLoaderExecutorQueueSize;
            this.defaultExecutorQueueSize = statistics.defaultExecutorQueueSize;
            this.asyncExecutorQueueSize = statistics.asyncExecutorQueueSize;
            this.eventExecutorQueueSize = statistics.eventExecutorQueueSize;
            this.mapStoreExecutorQueueSize = statistics.mapStoreExecutorQueueSize;
        }

        public boolean exceedsThreshold() {
            if (memoryUsedOfMaxPercentage > threshold) {
                return true;
            }

            if (processCpuLoad > threshold) {
                return true;
            }

            if (systemCpuLoad > threshold) {
                return true;
            }

            for (Integer size : outputQueueSizes.values()) {
                if (size * 100d / maxOutputQueueSize > threshold) {
                    return true;
                }
            }

            return false;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("memory.used=").append(bytesToString(memoryUsed)).append(", ");
            sb.append("memory.free=").append(bytesToString(memoryFree)).append(", ");
            sb.append("memory.total=").append(bytesToString(memoryTotal)).append(", ");
            sb.append("memory.max=").append(bytesToString(memoryMax)).append(", ");
            sb.append("memory.used/total=").append(percentageString(memoryUsedOfTotalPercentage)).append(" ");
            sb.append("memory.used/max=").append(percentageString(memoryUsedOfMaxPercentage)).append(" ");
            sb.append("load.process=").append(format("%.2f", processCpuLoad)).append("%, ");
            sb.append("load.system=").append(format("%.2f", systemCpuLoad)).append("%, ");
            sb.append("load.systemAverage=").append(format("%.2f", systemLoadAverage)).append("% ");
            sb.append("q.packet.size=").append(packetQueueSize).append(", ");
            sb.append("q.processable.size=").append(processableQueueSize).append(", ");
            sb.append("q.processablePriority.size=").append(processablePriorityQueueSize).append(", ");
            sb.append("thread.count=").append(threadCount).append(", ");
            sb.append("thread.peakCount=").append(peakThreadCount).append(", ");

            sb.append("q.query.size=").append(queryQueueSize).append(", ");
            sb.append("q.mapLoader.size=").append(mapLoaderExecutorQueueSize).append(", ");
            sb.append("q.defaultExecutor.size=").append(defaultExecutorQueueSize).append(", ");
            sb.append("q.asyncExecutor.size=").append(asyncExecutorQueueSize).append(", ");
            sb.append("q.eventExecutor.size=").append(eventExecutorQueueSize).append(", ");
            sb.append("q.mapStoreExecutor.size=").append(mapStoreExecutorQueueSize).append(", ");

            for (Map.Entry<Address, Integer> entry : outputQueueSizes.entrySet()) {
                final Integer size = entry.getValue();
                if (size * 100d / maxOutputQueueSize > threshold) {
                    sb.append("connection.outputQueue.size=").append(size);
                    sb.append("address=").append(entry.getKey()).append(", ");
                }
            }

            return sb.toString();
        }
    }

    private static final String[] UNITS = new String[]{"", "K", "M", "G", "T", "P", "E"};

    private static Long get(OperatingSystemMXBean mbean, String methodName, Long defaultValue) {
        try {
            Method method = mbean.getClass().getMethod(methodName);
            method.setAccessible(true);

            Object value = method.invoke(mbean);
            if (value == null) {
                return defaultValue;
            }

            if (value instanceof Integer) {
                return (long) (Integer) value;
            }

            if (value instanceof Double) {
                double v = (Double) value;
                return Math.round(v * 100);
            }

            if (value instanceof Long) {
                return (Long) value;
            }

            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static String percentageString(double p) {
        return format("%.2f", p) + "%";
    }

    public static String bytesToString(long bytes) {
        for (int i = 6; i > 0; i--) {
            double step = Math.pow(1024, i);
            if (bytes > step) return format("%3.1f%s", bytes / step, UNITS[i]);
        }
        return Long.toString(bytes);
    }
}
