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
        System.out.println("PASS: capability selection, property evidence, resource fallback, existing focus, missing requirements");
    }
    private static void check(AdaptationPlan plan, String configuration, String resources) {
        require(plan.supported() && plan.configuration.equals(configuration) && plan.resources.equals(resources),
            configuration + " / " + resources);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
