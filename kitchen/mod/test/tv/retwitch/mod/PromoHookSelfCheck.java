package tv.retwitch.mod;

/** JVM self-check for the Turbo query rewrite. Build/run like ads/PlaylistSelfCheck. */
public final class PromoHookSelfCheck {

    public static void main(String[] args) {
        String button = "{\"query\":\"query TurboUpsellButton($platform: OfferPlatform!) {\\n"
                + "  subscriptionProduct(productName: \\\"turbo\\\") {\\n    id\\n  }\\n}\"}";
        String tray = "{\"query\":\"query TurboTray {\\n  subscriptionProduct(productName: \\\"turbo\\\") {\\n    id\\n  }\\n}\"}";

        assert PromoHook.withoutTurboOffers(button).contains("productName: \\\"retwitch\\\"");
        assert !PromoHook.withoutTurboOffers(button).contains("\\\"turbo\\\"");
        assert PromoHook.withoutTurboOffers(tray) == tray;
        assert PromoHook.withoutTurboOffers(null) == null;

        String batch = "[" + tray + "," + button + "]";
        String rewritten = PromoHook.withoutTurboOffers(batch);
        assert rewritten.startsWith("[" + tray + ",") && rewritten.contains("retwitch");
        System.out.println("PromoHookSelfCheck OK");
    }
}
