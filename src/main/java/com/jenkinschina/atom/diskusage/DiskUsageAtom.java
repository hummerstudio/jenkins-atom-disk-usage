package com.jenkinschina.atom.diskusage;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * disk-usage as an atom: what the workspace and the agent's temp directories actually weigh.
 *
 * <p>Runs before a cleanup, or as a nightly check: "the workspace is 4.1 GB and 3.2 GB of it is .git" is the kind of
 * sentence a build log should be able to say.
 */
public class DiskUsageAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String label = context.getParam("label", "Disk usage");
        String outDir = context.getParam("outDir", "out/disk");
        String extraPaths = context.getParam("extraPaths", "");
        double maxMb = parse(context.getParam("maxMb", "0"), 0);
        int topN = Math.max(1, Math.min(context.getIntParam("topN", 15), 100));

        List<Entry> top = new ArrayList<>();
        long total = 0;
        long files = 0;
        Map<String, Long> byDirectory = new LinkedHashMap<>();

        try (var walk = Files.walk(workspace, 30)) {
            for (Path path : walk.toList()) {
                try {
                    if (Files.isRegularFile(path) && !isRunnerFile(workspace, path)) {
                        long size = Files.size(path);
                        total += size;
                        files++;
                        Path relative = workspace.relativize(path);
                        String top1 = relative.getNameCount() > 1 ? relative.getName(0).toString() : ".";
                        byDirectory.merge(top1, size, Long::sum);
                        top.add(new Entry(relative.toString(), size));
                    }
                } catch (Exception ignored) {
                    // a file removed mid-walk, or one we may not read
                }
            }
        }
        for (String extra : extraPaths.split(",")) {
            String trimmed = extra.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed);
            if (Files.isDirectory(path)) {
                long[] measured = measure(path);
                total += measured[0];
                files += measured[1];
            }
        }

        top.sort(Comparator.comparingLong(Entry::bytes).reversed());
        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"),
                render(label, total, files, byDirectory, top, topN), StandardCharsets.UTF_8);

        double totalMb = total / (1024.0 * 1024.0);
        context.log(String.format(Locale.ROOT, "[disk] %s in %d file(s); largest: %s (%s)",
                human(total), files, top.isEmpty() ? "-" : top.get(0).path(),
                top.isEmpty() ? "-" : human(top.get(0).bytes())));

        AtomResult result = AtomResult.success()
                .data("bytes", String.valueOf(total))
                .data("human", human(total))
                .data("files", String.valueOf(files))
                .data("mb", String.format(Locale.ROOT, "%.1f", totalMb))
                .report("report", label, dir.toString(), "index.html");

        if (maxMb > 0 && totalMb > maxMb) {
            return AtomResult.failure(String.format(Locale.ROOT, "%.1f MB used, the budget is %.1f MB", totalMb, maxMb))
                    .data("mb", String.format(Locale.ROOT, "%.1f", totalMb))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    /** input.json / output.json / .secrets.json / .atoms are the runner's, not the build's. */
    private static boolean isRunnerFile(Path workspace, Path path) {
        String relative = workspace.relativize(path).toString();
        if (relative.startsWith(".atoms/") || relative.startsWith(".atoms\\")) {
            return true;
        }
        return relative.equals("input.json") || relative.equals("output.json") || relative.equals(".secrets.json");
    }

    private static long[] measure(Path root) {
        long bytes = 0;
        long files = 0;
        try (var walk = Files.walk(root, 30)) {
            for (Path path : walk.toList()) {
                try {
                    if (Files.isRegularFile(path)) {
                        bytes += Files.size(path);
                        files++;
                    }
                } catch (Exception ignored) {
                    // unreadable
                }
            }
        } catch (Exception ignored) {
            // vanished
        }
        return new long[] {bytes, files};
    }

    private static String render(String label, long total, long files, Map<String, Long> byDirectory,
                                 List<Entry> top, int topN) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px;margin-bottom:22px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}")
                .append("th{background:#fafbfc;color:#6b7280}td.n{text-align:right;font-variant-numeric:tabular-nums}")
                .append(".bar{height:6px;background:#3b82f6;border-radius:3px}code{font-family:ui-monospace,Menlo,monospace}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">").append(human(total))
                .append(" across ").append(files).append(" file(s)</p>");

        html.append("<table><tr><th>Top level</th><th class=\"n\">Size</th><th>Share</th></tr>");
        byDirectory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15)
                .forEach(entry -> html.append("<tr><td>").append(escape(entry.getKey()))
                        .append("</td><td class=\"n\">").append(human(entry.getValue())).append("</td><td><div class=\"bar\" style=\"width:")
                        .append(Math.max(1, Math.round(100.0 * entry.getValue() / Math.max(1, total))))
                        .append("%\"></div></td></tr>"));
        html.append("</table>");

        html.append("<table><tr><th>Largest file</th><th class=\"n\">Size</th></tr>");
        for (Entry entry : top.subList(0, Math.min(topN, top.size()))) {
            html.append("<tr><td><code>").append(escape(entry.path())).append("</code></td><td class=\"n\">")
                    .append(human(entry.bytes())).append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        for (String unit : List.of("KiB", "MiB", "GiB")) {
            value /= 1024;
            if (value < 1024) {
                return String.format(Locale.ROOT, "%.1f %s", value, unit);
            }
        }
        return String.format(Locale.ROOT, "%.1f TiB", value / 1024);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static double parse(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Entry(String path, long bytes) {}
}
