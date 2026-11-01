package local.bluetooth.speaker;

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
        String configuration = roleProperties ? "profile-properties"
            : vendorPropertyReferences && roleResources ? "vendor-properties" : "none";
        String resources = "none";
        if (!"none".equals(configuration) && propertyWriter && focusResource) {
            if (!roleProperties && packagedMapping && roleResources) resources = "packaged-idmap";
            else if (fabricate) resources = "fabricated-overlay";
            else if (packagedMapping && roleResources) resources = "packaged-idmap";
            else if (roleProperties && focusAlreadyEnabled) resources = "system-default";
        }
        return new AdaptationPlan(configuration, resources);
    }

    boolean supported() { return !"none".equals(configuration) && !"none".equals(resources); }
}
