package tv.retwitch.mod;

/**
 * JVM self-check for {@link RnTheatreHook#route} against both enum spellings.
 * Run from kitchen/ (classpath as in PlaylistSelfCheck):
 *   javac -cp /opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar:libs/bugsnag-android-core-6.11.0.jar -d work/selfcheck mod/src/tv/retwitch/mod/ads/*.java mod/src/tv/retwitch/mod/*.java mod/src/tv/retwitch/mod/emotes/*.java mod/test/tv/retwitch/mod/RnTheatreHookSelfCheck.java
 *   java -ea -cp work/selfcheck:/opt/android-sdk/platforms/android-36/android.jar tv.retwitch.mod.RnTheatreHookSelfCheck
 */
public final class RnTheatreHookSelfCheck {

    enum V318 { Route, RefuseNotLiveChannel, RefuseUltralightOff }

    enum V319 { Route, RefuseSkyline, RefuseUltralightUnsupported }

    public static void main(String[] args) {
        assert RnTheatreHook.route(V318.Route) == V318.RefuseUltralightOff;
        assert RnTheatreHook.route(V319.Route) == V319.RefuseUltralightUnsupported;
        assert RnTheatreHook.route(V319.RefuseSkyline) == V319.RefuseSkyline;
        System.out.println("RnTheatreHookSelfCheck OK");
    }
}
