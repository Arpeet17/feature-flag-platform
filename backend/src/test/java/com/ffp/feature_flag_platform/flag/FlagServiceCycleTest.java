package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.evaluation.model.Prerequisite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlagServiceCycleTest {

    @Test
    void findsTransitiveCycleThroughStart() {
        var graph = Map.of(
                "a", List.of(new Prerequisite("b", 0)),
                "b", List.of(new Prerequisite("c", 0)),
                "c", List.of(new Prerequisite("a", 0)));
        assertThat(FlagService.findCycle("a", graph)).contains(List.of("a", "b", "c", "a"));
    }

    @Test
    void ignoresDiamondsAndUnrelatedCycles() {
        var graph = Map.of(
                "a", List.of(new Prerequisite("b", 0), new Prerequisite("c", 0)),
                "b", List.of(new Prerequisite("d", 0)),
                "c", List.of(new Prerequisite("d", 0)),
                "x", List.of(new Prerequisite("y", 0)),
                "y", List.of(new Prerequisite("x", 0)));
        assertThat(FlagService.findCycle("a", graph)).isEmpty();
    }
}
