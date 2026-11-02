package local.bluetooth.speaker;

public final class AdaptationPlanTest {
    public static void main(String[] args) {
        check(AdaptationPlan.choose(true, false, false, true, false, true, false, true),
            "profile-properties", "fabricated-overlay");
        check(AdaptationPlan.choose(false, true, true, true, false, false, true, true),
            "vendor-properties", "packaged-idmap");
        check(AdaptationPlan.choose(false, true, true, true, false, true, false, true),
            "vendor-properties", "fabricated-overlay");
        check(AdaptationPlan.choose(true, false, true, true, false, false, true, true),
            "profile-properties", "packaged-idmap");
        check(AdaptationPlan.choose(true, false, false, true, true, false, false, true),
            "profile-properties", "system-default");
        require(!AdaptationPlan.choose(false, false, true, true, true, true, true, true).supported(),
            "resources alone must not cause unverified property writes");
        require(!AdaptationPlan.choose(false, true, false, true, true, true, true, true).supported(),
            "vendor path requires role resources");
        require(!AdaptationPlan.choose(true, false, true, false, false, true, true, true).supported(),
            "missing focus resource");
        require(!AdaptationPlan.choose(true, true, true, true, true, true, true, false).supported(),
            "missing property writer");
        java.util.List<AdaptationPlan> plans = AdaptationPlan.candidates(true, true, true, true, true, true, true, true);
        require(plans.size() == 5, "both protocols must remain available for fallback");
        require(plans.get(0).id().equals("vendor-properties/fabricated-overlay"), "vendor evidence and dynamic resources first");
        require(plans.get(1).id().equals("vendor-properties/packaged-idmap"), "vendor resource fallback");
        require(plans.get(2).id().equals("profile-properties/fabricated-overlay"), "AOSP remains a candidate");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (AdaptationPlan plan : plans) {
            require(ids.add(plan.id()), "duplicate candidate");
            require(AdaptationPlan.fromId(plan.id()) != null, "manual selection must round-trip");
        }
        require(AdaptationPlan.fromId("vendor-properties/system-default") == null,
            "vendor protocol cannot bypass role resources");
        require(AdaptationPlan.fromId("unknown/fabricated-overlay") == null, "unknown manual selection");
        require(AdaptationPlan.candidates(false, true, true, true, false, false, true, true).size() == 1,
            "absent fabrication tool must not be offered");
        require(AdaptationPlan.candidates(true, false, false, true, false, false, true, true).isEmpty(),
            "packaged mapping requires all role resources");
        System.out.println("PASS: candidates, ordering, protocol evidence, resource fallback, manual IDs, missing requirements");
    }
    private static void check(AdaptationPlan plan, String configuration, String resources) {
        require(plan.supported() && plan.configuration.equals(configuration) && plan.resources.equals(resources),
            configuration + " / " + resources);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
