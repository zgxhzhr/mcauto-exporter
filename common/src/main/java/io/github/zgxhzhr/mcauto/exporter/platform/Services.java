package io.github.zgxhzhr.mcauto.exporter.platform;

import io.github.zgxhzhr.mcauto.exporter.ExporterLog;
import io.github.zgxhzhr.mcauto.exporter.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    private static <T> T load(Class<T> clazz) {
        T service = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("缺少平台实现: " + clazz.getName()));
        ExporterLog.LOG.debug("已加载平台服务 {}", service);
        return service;
    }
}
