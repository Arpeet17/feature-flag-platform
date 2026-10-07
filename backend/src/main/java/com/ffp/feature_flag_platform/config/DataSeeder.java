package com.ffp.feature_flag_platform.config;

import com.ffp.feature_flag_platform.auth.Role;
import com.ffp.feature_flag_platform.auth.User;
import com.ffp.feature_flag_platform.auth.UserRepository;
import com.ffp.feature_flag_platform.evaluation.model.Clause;
import com.ffp.feature_flag_platform.evaluation.model.FlagType;
import com.ffp.feature_flag_platform.evaluation.model.Operator;
import com.ffp.feature_flag_platform.evaluation.model.Prerequisite;
import com.ffp.feature_flag_platform.evaluation.model.Rollout;
import com.ffp.feature_flag_platform.evaluation.model.Rule;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition.SegmentRule;
import com.ffp.feature_flag_platform.evaluation.model.Target;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.evaluation.model.VariationOrRollout;
import com.ffp.feature_flag_platform.evaluation.model.WeightedVariation;
import com.ffp.feature_flag_platform.flag.FlagDtos.CreateFlagRequest;
import com.ffp.feature_flag_platform.flag.FlagDtos.UpdateTargetingRequest;
import com.ffp.feature_flag_platform.flag.FlagService;
import com.ffp.feature_flag_platform.project.ProjectDtos.CreateProjectRequest;
import com.ffp.feature_flag_platform.project.ProjectRepository;
import com.ffp.feature_flag_platform.project.ProjectService;
import com.ffp.feature_flag_platform.segment.SegmentDtos.CreateSegmentRequest;
import com.ffp.feature_flag_platform.segment.SegmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bootstraps the first admin user (any environment) and, when enabled, a realistic demo
 * project. Demo data is created through the regular services so it passes the same
 * validation and produces audit entries like any user change.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    static final String DEMO_PROJECT = "demo-app";

    private final AppProperties.Bootstrap bootstrap;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ProjectRepository projectRepository;
    private final ProjectService projects;
    private final FlagService flags;
    private final SegmentService segments;
    private final TransactionTemplate tx;

    public DataSeeder(AppProperties properties, UserRepository users, PasswordEncoder passwordEncoder,
                      ProjectRepository projectRepository, ProjectService projects, FlagService flags,
                      SegmentService segments, TransactionTemplate tx) {
        this.bootstrap = properties.bootstrap();
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.projectRepository = projectRepository;
        this.projects = projects;
        this.flags = flags;
        this.segments = segments;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> {
            seedUsers();
            if (bootstrap.demoData() && !projectRepository.existsByKey(DEMO_PROJECT)) {
                seedDemoProject();
                log.info("Seeded demo project '{}'", DEMO_PROJECT);
            }
        });
    }

    private void seedUsers() {
        if (users.count() > 0 || bootstrap.adminEmail() == null || bootstrap.adminPassword() == null) {
            return;
        }
        users.save(new User(bootstrap.adminEmail(), passwordEncoder.encode(bootstrap.adminPassword()), "Admin", Role.ADMIN));
        log.info("Created bootstrap admin user {}", bootstrap.adminEmail());
        if (bootstrap.demoData()) {
            users.save(new User("editor@ffp.local", passwordEncoder.encode("editor123"), "Erin Editor", Role.EDITOR));
            users.save(new User("viewer@ffp.local", passwordEncoder.encode("viewer123"), "Victor Viewer", Role.VIEWER));
        }
    }

    private void seedDemoProject() {
        projects.create(new CreateProjectRequest(DEMO_PROJECT, "Demo Storefront",
                "Sample e-commerce app showing releases, experiments, kill switches and targeting."), bootstrap.demoSdkKeys());

        segments.create(DEMO_PROJECT, new CreateSegmentRequest("beta-testers", "Beta testers",
                "Customers who opted into early access", Set.of("user-1", "user-2", "user-42"), Set.of(), List.of()));
        segments.create(DEMO_PROJECT, new CreateSegmentRequest("internal-users", "Internal users",
                "Employees, matched by email domain", Set.of(), Set.of(),
                List.of(new SegmentRule(List.of(Clause.of("email", Operator.ENDS_WITH, "@acme.com"))))));

        // 1. Progressive release with a percentage rollout.
        createBoolean("new-checkout", "New checkout flow", "One-page checkout redesign", Set.of("checkout", "release"), true);
        target("new-checkout", "development", true, Targeting.simple(0, 1));
        target("new-checkout", "staging", true, new Targeting(1, List.of(), List.of(),
                List.of(rule("Internal users", segmentClause("internal-users"), fixed(0))), rollout(50_000, 50_000)));
        target("new-checkout", "production", true, new Targeting(1, List.of(), List.of(new Target(0, Set.of("vip-customer"))),
                List.of(rule("Beta testers", segmentClause("beta-testers"), fixed(0))), rollout(10_000, 90_000)));

        // 2. Dependent feature: only for users who already have the new checkout.
        createBoolean("checkout-upsell", "Checkout upsell widget", "Recommends add-ons in the new checkout",
                Set.of("checkout"), true);
        for (String env : List.of("staging", "production")) {
            target("checkout-upsell", env, true, new Targeting(1, List.of(new Prerequisite("new-checkout", 0)),
                    List.of(), List.of(), fixed(0)));
        }

        // 3. A/B/n experiment on a string flag.
        flags.create(DEMO_PROJECT, new CreateFlagRequest("search-algorithm", "Search ranking algorithm",
                "Experiment comparing ranking strategies", FlagType.STRING,
                List.of(variation(JSON.stringNode("bm25"), "Control (BM25)"),
                        variation(JSON.stringNode("semantic"), "Semantic"),
                        variation(JSON.stringNode("hybrid"), "Hybrid")),
                0, 0, Set.of("experiment", "search"), true));
        target("search-algorithm", "production", true, new Targeting(0, List.of(), List.of(), List.of(),
                VariationOrRollout.rollout(new Rollout(List.of(new WeightedVariation(0, 34_000),
                        new WeightedVariation(1, 33_000), new WeightedVariation(2, 33_000)), "key"))));

        // 4. Numeric config by plan, using attribute targeting.
        flags.create(DEMO_PROJECT, new CreateFlagRequest("max-upload-size-mb", "Max upload size (MB)",
                "Upload limit per plan", FlagType.NUMBER,
                List.of(variation(JSON.numberNode(10), "Free"), variation(JSON.numberNode(50), "Pro"),
                        variation(JSON.numberNode(500), "Enterprise")),
                0, 0, Set.of("limits"), false));
        for (String env : List.of("development", "staging", "production")) {
            target("max-upload-size-mb", env, true, new Targeting(0, List.of(), List.of(), List.of(
                    rule("Pro plan", Clause.of("plan", Operator.IN, "pro"), fixed(1)),
                    rule("Enterprise plan", Clause.of("plan", Operator.IN, "enterprise"), fixed(2))), fixed(0)));
        }

        // 5. Version-gated mobile feature: appVersion >= 2.3.0 (negated SEMVER_LESS_THAN).
        createBoolean("mobile-dark-mode", "Mobile dark mode", "Requires app 2.3.0+", Set.of("mobile"), true);
        target("mobile-dark-mode", "production", true, new Targeting(1, List.of(), List.of(), List.of(
                rule("App 2.3.0 or newer", new Clause("appVersion", Operator.SEMVER_LESS_THAN, List.of("2.3.0"), true), fixed(0))),
                fixed(1)));

        // 6. Remote JSON configuration.
        var pricing = JSON.objectNode().put("currency", "USD").put("discountPercent", 0).put("showAnnualToggle", false);
        var festive = JSON.objectNode().put("currency", "INR").put("discountPercent", 20).put("showAnnualToggle", true);
        flags.create(DEMO_PROJECT, new CreateFlagRequest("pricing-config", "Pricing page config",
                "Remote configuration for the pricing page", FlagType.JSON,
                List.of(variation(pricing, "Default"), variation(festive, "Festive India")), 0, 0, Set.of("pricing"), false));
        target("pricing-config", "production", true, new Targeting(0, List.of(), List.of(), List.of(
                rule("India", Clause.of("country", Operator.IN, "IN"), fixed(1))), fixed(0)));

        // 7. Operational kill switch: permanent, on everywhere.
        createBoolean("payments-enabled", "Payments kill switch", "Turn off to stop accepting payments during an incident",
                Set.of("ops", "kill-switch"), false);
        for (String env : List.of("development", "staging", "production")) {
            target("payments-enabled", env, true, Targeting.simple(0, 1));
        }
    }

    private void createBoolean(String key, String name, String description, Set<String> tags, boolean temporary) {
        flags.create(DEMO_PROJECT, new CreateFlagRequest(key, name, description, FlagType.BOOLEAN, null, 0, 1, tags, temporary));
    }

    private void target(String flagKey, String env, boolean enabled, Targeting targeting) {
        long version = flags.getConfig(DEMO_PROJECT, flagKey, env).version();
        flags.updateTargeting(DEMO_PROJECT, flagKey, env, new UpdateTargetingRequest(targeting, enabled, version, "Demo setup"));
    }

    private static Rule rule(String description, Clause clause, VariationOrRollout serve) {
        return new Rule(null, description, List.of(clause), serve);
    }

    private static Clause segmentClause(String segmentKey) {
        return new Clause(null, Operator.SEGMENT_MATCH, List.of(segmentKey), false);
    }

    private static VariationOrRollout fixed(int variation) {
        return VariationOrRollout.fixed(variation);
    }

    private static VariationOrRollout rollout(int trueWeight, int falseWeight) {
        return VariationOrRollout.rollout(new Rollout(List.of(new WeightedVariation(0, trueWeight),
                new WeightedVariation(1, falseWeight)), "key"));
    }

    private static Variation variation(JsonNode value, String name) {
        return new Variation(value, name, null);
    }

    Map<String, String> demoSdkKeys() {
        return bootstrap.demoSdkKeys();
    }
}
