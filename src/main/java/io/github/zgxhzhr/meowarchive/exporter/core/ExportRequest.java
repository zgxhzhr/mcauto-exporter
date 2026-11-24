package io.github.zgxhzhr.meowarchive.exporter.core;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ExportRequest {
    public final ExportMode mode;
    public final Set<String> namespaces = new LinkedHashSet<>();
    public final Set<Target.Kind> kinds = new LinkedHashSet<>();
    public final Set<Integer> sizes = new LinkedHashSet<>();
    public Target singleTarget;

    public ExportRequest(ExportMode mode) {
        this.mode = mode;
    }
}
