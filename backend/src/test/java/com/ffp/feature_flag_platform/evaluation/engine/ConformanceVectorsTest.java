package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.common.Json;
import com.ffp.feature_flag_platform.evaluation.model.Clause;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationContext;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationResult;
import com.ffp.feature_flag_platform.evaluation.model.FlagDefinition;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the shared cross-language vectors in {@code conformance/evaluation-vectors.json} against
 * the Java reference engine. The TypeScript SDK runs the very same file, so any divergence
 * between server-side and SDK-side evaluation fails one of the two builds.
 *
 * <p>Cases marked {@code "generated": true} (hashes, buckets, rollout assignments) take their
 * expectations from this engine: regenerate them with {@code -Dconformance.regenerate=true}
 * after an intentional change. Hand-written cases encode the spec and are never overwritten.
 */
class ConformanceVectorsTest {

    private static final Path FILE = Path.of("..", "conformance", "evaluation-vectors.json");
    private static final boolean REGENERATE = Boolean.getBoolean("conformance.regenerate");

    /** 1 == 1L == 1.0: JSON has one number type, Jackson has several. */
    private static final java.util.Comparator<JsonNode> NUMERIC_AWARE = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
    };

    @TestFactory
    Stream<DynamicTest> conformance() throws Exception {
        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(Files.readString(FILE));
        Map<String, FlagDefinition> flags = Json.MAPPER.convertValue(root.get("flags"), new TypeReference<>() { });
        Map<String, SegmentDefinition> segments = Json.MAPPER.convertValue(root.get("segments"), new TypeReference<>() { });
        FlagEvaluator evaluator = new FlagEvaluator(flags::get, segments::get);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode node : root.get("murmur3")) {
            ObjectNode c = (ObjectNode) node;
            int actual = MurmurHash3.hash32(c.get("input").asString().getBytes(StandardCharsets.UTF_8), c.get("seed").asInt());
            tests.add(check("murmur3 \"" + c.get("input").asString() + "\" seed " + c.get("seed").asInt(), c,
                    JsonNodeFactory.instance.numberNode(Integer.toUnsignedLong(actual))));
        }
        for (JsonNode node : root.get("buckets")) {
            ObjectNode c = (ObjectNode) node;
            int bucket = Bucketer.bucket(c.get("flagKey").asString(), c.get("salt").asString(), c.get("value").asString());
            tests.add(check("bucket " + c.get("flagKey").asString() + "/" + c.get("value").asString(), c,
                    JsonNodeFactory.instance.numberNode(bucket)));
        }
        for (JsonNode node : root.get("clauses")) {
            ObjectNode c = (ObjectNode) node;
            Clause clause = Json.MAPPER.convertValue(c.get("clause"), Clause.class);
            boolean actual = ClauseMatcher.matches(clause, context(c.get("context")), null);
            tests.add(check("clause: " + c.get("description").asString(), c, JsonNodeFactory.instance.booleanNode(actual)));
        }
        for (JsonNode node : root.get("evaluations")) {
            ObjectNode c = (ObjectNode) node;
            EvaluationResult result = evaluator.evaluate(c.get("flag").asString(), context(c.get("context")));
            ObjectNode actual = JsonNodeFactory.instance.objectNode();
            actual.set("value", result.value() == null ? JsonNodeFactory.instance.nullNode() : result.value());
            actual.set("variation", result.variationIndex() == null ? JsonNodeFactory.instance.nullNode()
                    : JsonNodeFactory.instance.numberNode(result.variationIndex()));
            actual.set("reason", Json.MAPPER.valueToTree(result.reason()));
            tests.add(check("evaluate: " + c.get("description").asString(), c, actual));
        }

        if (REGENERATE) {
            Files.writeString(FILE, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        }
        return tests.stream();
    }

    /** Fills generated expectations when regenerating; otherwise asserts. */
    private static DynamicTest check(String name, ObjectNode testCase, JsonNode actual) {
        boolean generated = testCase.path("generated").asBoolean(false);
        if (REGENERATE && generated) {
            testCase.set("expected", actual);
        }
        JsonNode expected = testCase.get("expected");
        return DynamicTest.dynamicTest(name, () -> {
            if (expected == null || expected.isNull() && generated) {
                fail("No expectation recorded; run with -Dconformance.regenerate=true");
            }
            assertThat(actual.equals(NUMERIC_AWARE, expected))
                    .as("%s%n  expected: %s%n  actual:   %s", name, expected, actual).isTrue();
        });
    }

    private static EvaluationContext context(JsonNode node) {
        Map<String, Object> attributes = node.has("attributes")
                ? Json.MAPPER.convertValue(node.get("attributes"), new TypeReference<Map<String, Object>>() { })
                : Map.of();
        return new EvaluationContext(node.get("key").asString(), attributes);
    }
}
