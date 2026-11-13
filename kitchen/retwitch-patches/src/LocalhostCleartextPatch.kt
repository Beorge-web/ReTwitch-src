package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Allows cleartext HTTP to 127.0.0.1 so the player can fetch playlists from
 * the on-device VAFT proxy ({@code tv.retwitch.mod.ads.VaftServer}). Twitch's
 * `res/xml/default_security_config.xml` sets
 * `base-config cleartextTrafficPermitted="false"`, which makes OkHttp refuse
 * `http://127.0.0.1` outright. Only loopback is exempted; everything else
 * keeps Twitch's policy.
 */
val retwitchLocalhostCleartextPatch = resourcePatch(
    name = "ReTwitch localhost cleartext",
    description = "Permits cleartext HTTP to 127.0.0.1 for the on-device ad-filtering playlist proxy.",
) {
    compatibleWith(twitchCompatibility)

    execute {
        document("res/xml/default_security_config.xml").use { document ->
            val config = document.getElementsByTagName("network-security-config").item(0) as Element
            val domains = document.getElementsByTagName("domain")
            for (i in 0 until domains.length) {
                if (domains.item(i).textContent.trim() == "127.0.0.1") {
                    return@use
                }
            }
            val domainConfig = document.createElement("domain-config")
            domainConfig.setAttribute("cleartextTrafficPermitted", "true")
            val domain = document.createElement("domain")
            domain.setAttribute("includeSubdomains", "false")
            domain.textContent = "127.0.0.1"
            domainConfig.appendChild(domain)
            config.appendChild(domainConfig)
        }
    }
}
