package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * AppMetrica's own service from its AAR manifest: the client binds to it to
 * store and send reports. The AAR's PreloadInfoContentProvider (preinstall
 * tracking for retail partners) is not needed.
 */
private val appMetricaServicePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val service = document.createElement("service")
            service.setAttribute("android:name", "io.appmetrica.analytics.internal.AppMetricaService")
            service.setAttribute("android:enabled", "true")
            service.setAttribute("android:exported", "false")
            val filter = document.createElement("intent-filter")
            for ((tag, attribute, value) in listOf(
                Triple("category", "android:name", "android.intent.category.DEFAULT"),
                Triple("action", "android:name", "io.appmetrica.analytics.IAppMetricaService"),
                Triple("data", "android:scheme", "appmetrica"),
            )) {
                filter.appendChild(document.createElement(tag).apply { setAttribute(attribute, value) })
            }
            service.appendChild(filter)
            application.appendChild(service)
        }
    }
}

/**
 * Wires up app-wide crash reporting and statistics (AppMetrica) at process start.
 *
 * Injects a one-liner at the very top of the app's own
 * {@code Application.onCreate} that hands the process Application to
 * [tv.retwitch.mod.CrashReporter], which calls {@code AppMetrica.activate(...)}
 * to register a process-wide uncaught-exception handler. Injecting first (index 0)
 * means the handler is in place before any of Twitch's own onCreate work runs,
 * so an early-startup crash is still captured.
 *
 * The Application class ([APP_CLASS]) is declared in AndroidManifest.xml, so
 * its name is one of the few Twitch identifiers R8 must keep stable across
 * releases (same reasoning as [retwitchDisplayCutoutPatch], which hooks the
 * same method). If Twitch ever renames/repackages its Application, the
 * single()/error() below fails the
 * build loudly instead of silently no-op'ing at runtime.
 */
private const val APP_CLASS = "Ltv/twitch/android/app/consumer/TwitchApplication;"
private const val HOOK = "Ltv/retwitch/mod/CrashReporter;"

val retwitchCrashReporterPatch = bytecodePatch(
    name = "ReTwitch crash reporter",
    description = "Captures and reports app-wide Java/Kotlin crashes via AppMetrica.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch, appMetricaServicePatch)

    execute {
        val application = mutableClassDefByOrNull { it.type == APP_CLASS }
            ?: error("Application class $APP_CLASS not found")

        val onCreate = application.methods.single {
            it.name == "onCreate" &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty()
        }

        // p0 is `this` (the TwitchApplication, an android.app.Application).
        // onCreate has many locals, so `this` sits above v15 -> the /range
        // form is required (non-range invokes only encode v0-v15).
        onCreate.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $HOOK->install(Landroid/app/Application;)V",
        )
    }
}
