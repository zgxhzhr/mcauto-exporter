package io.github.zgxhzhr.meowarchive.exporter.core;

public enum ExportMode {
    FULL("完整导出（JSON + 图标）"),
    JSON_ONLY("只导出 JSON"),
    ICONS_ONLY("只导出图标"),
    SINGLE_ICON("导出单个图标");

    public final String label;

    ExportMode(String label) {
        this.label = label;
    }
}
