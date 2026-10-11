package com.ffp.feature_flag_platform.evaluation.model;

/** This flag is only evaluated normally if {@code flagKey} serves {@code variation}. */
public record Prerequisite(String flagKey, int variation) {
}
