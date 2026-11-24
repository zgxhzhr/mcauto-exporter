package io.github.zgxhzhr.meowarchive.exporter.platform;

import io.github.zgxhzhr.meowarchive.exporter.ExporterLog;
import io.github.zgxhzhr.meowarchive.exporter.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    private static <T> T load(Class<T> clazz) {
        java.util.Iterator<T> iterator = ServiceLoader.load(clazz).iterator();
        if (iterator.hasNext()) {
            T service = iterator.next();
            ExporterLog.LOG.debug("已加载平台服务 {}", service);
            return service;
        }
        // 1.15.2 的 Forge ModLauncher 不会把模组 jar 里的 META-INF/services 交给
        // ServiceLoader（TCCL 不是 TransformingClassLoader），这里按已知实现类回退。
        for (String candidate : new String[]{
                "io.github.zgxhzhr.meowarchive.exporter.platform.ForgePlatformHelper",
                "io.github.zgxhzhr.meowarchive.exporter.platform.FabricPlatformHelper"}) {
            try {
                Object instance = Class.forName(candidate).getDeclaredConstructor().newInstance();
                if (clazz.isInstance(instance)) {
                    T service = clazz.cast(instance);
                    ExporterLog.LOG.debug("已加载平台服务 {}（回退实例化）", service);
                    return service;
                }
            } catch (Throwable ignored) {
                // 该平台没有这个实现，继续试下一个
            }
        }
        throw new NullPointerException("缺少平台实现: " + clazz.getName());
    }
}
