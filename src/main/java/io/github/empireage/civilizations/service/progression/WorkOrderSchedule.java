package io.github.empireage.civilizations.service.progression;

import io.github.empireage.civilizations.config.WorkOrderCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/** Pure weighted selection policy for persistent category rotation. */
public final class WorkOrderSchedule {
    private WorkOrderSchedule() {}

    public static WorkOrderCatalog.Template select(WorkOrderCatalog catalog, WorkOrderCatalog.Category category,
                                                    Set<String> unlocked, List<String> recentKeys, long seed) {
        List<WorkOrderCatalog.Template> eligible = catalog.category(category).stream()
            .filter(template -> unlocked.containsAll(template.requiredTechnologies()))
            .sorted(Comparator.comparing(WorkOrderCatalog.Template::key))
            .toList();
        if (eligible.isEmpty()) throw new IllegalStateException("No eligible " + category + " work orders are configured");
        List<WorkOrderCatalog.Template> candidates = avoidRecent(eligible, recentKeys, 2);
        if (candidates.isEmpty()) candidates = avoidRecent(eligible, recentKeys, 1);
        if (candidates.isEmpty()) candidates = new ArrayList<>(eligible);
        long totalWeight = candidates.stream().mapToLong(WorkOrderCatalog.Template::weight).sum();
        long draw = new SplittableRandom(seed).nextLong(totalWeight);
        long cursor = 0;
        for (WorkOrderCatalog.Template candidate : candidates) {
            cursor += candidate.weight();
            if (draw < cursor) return candidate;
        }
        return candidates.getLast();
    }

    private static List<WorkOrderCatalog.Template> avoidRecent(List<WorkOrderCatalog.Template> source,
                                                                List<String> recentKeys, int count) {
        Set<String> avoided = recentKeys.stream().limit(count).collect(java.util.stream.Collectors.toSet());
        return source.stream().filter(template -> !avoided.contains(template.key()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
