package io.github.zgxhzhr.meowarchive.exporter;

import java.lang.reflect.Method;

/**
 * 日志门面：不直接依赖 slf4j。
 * 1.16.5 Forge 的运行期不为模组暴露 org.slf4j（只有安装器里用到），直接编译依赖会在加载模组时
 * 抛 NoClassDefFoundError。这里用反射探测 log4j2，找不到就退回标准输出，保证所有版本都能跑。
 */
public final class ExporterLog {
    public static final Logger LOG = new Logger("meowarchive");

    private ExporterLog() {
    }

    public static final class Logger {
        private final String name;
        private final Object delegate;

        Logger(String name) {
            this.name = name;
            this.delegate = createDelegate(name);
        }

        private static Object createDelegate(String name) {
            try {
                Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
                Method getLogger = logManager.getMethod("getLogger", String.class);
                return getLogger.invoke(null, name);
            } catch (Throwable ignored) {
                return null;
            }
        }

        public void info(String message, Object... args) {
            log("info", message, args);
        }

        public void warn(String message, Object... args) {
            log("warn", message, args);
        }

        public void error(String message, Object... args) {
            log("error", message, args);
        }

        public void debug(String message, Object... args) {
            log("debug", message, args);
        }

        private void log(String level, String message, Object... args) {
            Throwable throwable = null;
            int count = args == null ? 0 : args.length;
            if (count > 0 && args[count - 1] instanceof Throwable) {
                throwable = (Throwable) args[count - 1];
                count--;
            }
            String text = format(message, args, count);
            if (delegate != null) {
                try {
                    if (throwable != null) {
                        Method m = delegate.getClass().getMethod(level, String.class, Throwable.class);
                        m.invoke(delegate, text, throwable);
                    } else {
                        Method m = delegate.getClass().getMethod(level, String.class);
                        m.invoke(delegate, text);
                    }
                    return;
                } catch (Throwable ignored) {
                    // 反射失败就落到标准输出
                }
            }
            System.out.println("[" + name + "/" + level.toUpperCase() + "] " + text);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
        }

        private static String format(String message, Object[] args, int count) {
            if (message == null || count == 0) {
                return message;
            }
            StringBuilder out = new StringBuilder();
            int index = 0;
            int arg = 0;
            while (true) {
                int at = message.indexOf("{}", index);
                if (at < 0 || arg >= count) {
                    out.append(message.substring(index));
                    return out.toString();
                }
                out.append(message, index, at);
                out.append(String.valueOf(args[arg++]));
                index = at + 2;
            }
        }
    }
}
