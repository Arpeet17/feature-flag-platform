package com.ffp.feature_flag_platform.common;

import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition.SegmentRule;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Set;

/** JPA converters that persist small aggregates as JSON text (portable across H2 and Postgres). */
public final class JsonConverters {

    private JsonConverters() {
    }

    @Converter
    public static class TargetingConverter implements AttributeConverter<Targeting, String> {
        @Override
        public String convertToDatabaseColumn(Targeting attribute) {
            return Json.write(attribute);
        }

        @Override
        public Targeting convertToEntityAttribute(String dbData) {
            return Json.read(dbData, Targeting.class);
        }
    }

    @Converter
    public static class VariationListConverter implements AttributeConverter<List<Variation>, String> {
        private static final TypeReference<List<Variation>> TYPE = new TypeReference<>() { };

        @Override
        public String convertToDatabaseColumn(List<Variation> attribute) {
            return Json.write(attribute);
        }

        @Override
        public List<Variation> convertToEntityAttribute(String dbData) {
            return dbData == null ? List.of() : Json.read(dbData, TYPE);
        }
    }

    @Converter
    public static class SegmentRuleListConverter implements AttributeConverter<List<SegmentRule>, String> {
        private static final TypeReference<List<SegmentRule>> TYPE = new TypeReference<>() { };

        @Override
        public String convertToDatabaseColumn(List<SegmentRule> attribute) {
            return Json.write(attribute == null ? List.of() : attribute);
        }

        @Override
        public List<SegmentRule> convertToEntityAttribute(String dbData) {
            return dbData == null ? List.of() : Json.read(dbData, TYPE);
        }
    }

    @Converter
    public static class StringSetConverter implements AttributeConverter<Set<String>, String> {
        private static final TypeReference<Set<String>> TYPE = new TypeReference<>() { };

        @Override
        public String convertToDatabaseColumn(Set<String> attribute) {
            return Json.write(attribute == null ? Set.of() : new java.util.TreeSet<>(attribute));
        }

        @Override
        public Set<String> convertToEntityAttribute(String dbData) {
            return dbData == null ? Set.of() : Json.read(dbData, TYPE);
        }
    }
}
