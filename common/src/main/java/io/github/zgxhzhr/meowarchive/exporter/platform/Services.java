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
        if (!iterator.hasNext()) {
            throw new NullPointerException("缺少平台实现: " + clazz.getName());
        }
        T service = iterator.next();
        ExporterLog.LOG.debug("已加载平台服务 {}", service);
        return service;
    }
}
