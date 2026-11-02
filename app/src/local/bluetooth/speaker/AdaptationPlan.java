package local.bluetooth.speaker;

import java.util.ArrayList;
import java.util.List;

final class AdaptationPlan {
    final String configuration;
    final String resources;

    private AdaptationPlan(String configuration, String resources) {
        this.configuration = configuration;
        this.resources = resources;
    }

    static AdaptationPlan choose(boolean roleProperties, boolean vendorPropertyReferences,
            boolean roleResources, boolean focusResource, boolean focusAlreadyEnabled,
            boolean fabricate, boolean packagedMapping, boolean propertyWriter) {
        List<AdaptationPlan> plans = candidates(roleProperties, vendorPropertyReferences,
            roleResources, focusResource, focusAlreadyEnabled, fabricate, packagedMapping, propertyWriter);
        return plans.isEmpty() ? new AdaptationPlan("none", "none") : plans.get(0);
    }

    static List<AdaptationPlan> candidates(boolean roleProperties, boolean vendorPropertyReferences,
            boolean roleResources, boolean focusResource, boolean focusAlreadyEnabled,
            boolean fabricate, boolean packagedMapping, boolean propertyWriter) {
        List<AdaptationPlan> plans = new ArrayList<>();
        if (!propertyWriter || !focusResource) return plans;
        if (vendorPropertyReferences && roleResources) {
            add(plans, "vendor-properties", roleResources, false, fabricate, packagedMapping);
        }
        if (roleProperties) {
            add(plans, "profile-properties", roleResources, focusAlreadyEnabled, fabricate, packagedMapping);
        }
        return plans;
    }

    private static void add(List<AdaptationPlan> plans, String configuration, boolean roleResources,
            boolean focusAlreadyEnabled, boolean fabricate, boolean packagedMapping) {
        if (fabricate) plans.add(new AdaptationPlan(configuration, "fabricated-overlay"));
        if (packagedMapping && roleResources) plans.add(new AdaptationPlan(configuration, "packaged-idmap"));
        if (focusAlreadyEnabled) plans.add(new AdaptationPlan(configuration, "system-default"));
    }

    static AdaptationPlan fromId(String id) {
        if ("existing-module".equals(id)) return new AdaptationPlan("existing-module", "existing-module");
        for (AdaptationPlan plan : candidates(true, true, true, true, true, true, true, true)) {
            if (plan.id().equals(id)) return plan;
        }
        return null;
    }

    String id() {
        return "existing-module".equals(configuration) ? configuration : configuration + "/" + resources;
    }

    static String label(String id) {
        if ("auto".equals(id)) return "自动";
        if ("existing-module".equals(id)) return "已有兼容模块";
        AdaptationPlan plan = fromId(id);
        if (plan == null) return "不可用的方案";
        return ("vendor-properties".equals(plan.configuration) ? "厂商" : "AOSP")
            + ("fabricated-overlay".equals(plan.resources) ? " · 动态覆盖"
                : "packaged-idmap".equals(plan.resources) ? " · 资源包" : " · 系统默认资源");
    }

    boolean supported() { return !"none".equals(configuration) && !"none".equals(resources); }
}
