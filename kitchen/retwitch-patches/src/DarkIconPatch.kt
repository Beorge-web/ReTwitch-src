package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

/**
 * Launcher icons and the dark splash. The launcher entry moves from Twitch's launcher activity to
 * one activity-alias per icon (`AppIcon` in the mod enables one of them): stars (default: Twitch's
 * own icon with a starry tile), stock, dark (the brand kit's purple glitch on `hinted_grey_1`) and black (Twitch's
 * own black glitch on the same tile: the dark icon before 0.5.20, back on a tester's request). Each icon is an
 * adaptive icon with Twitch's own foreground plus a legacy PNG per density for launchers that don't
 * use adaptive icons. The system splash keeps the dark background with a purple-outlined copy of
 * the glitch (`rt_splash_foreground`), so its black outline stays visible. Twitch's own launch
 * cover (`viewer_landing` `splash_overlay`: a code-drawn purple `SplashGradientView` under the
 * animated glitch) gets the dark background; the gradient is sized to nothing, since Twitch's
 * animator may reset its alpha or visibility. Skipped softly.
 */
val retwitchDarkIconPatch = resourcePatch(
    name = "ReTwitch dark icon",
    description = "Launcher icon choice and dark splash screen.",
) {
    compatibleWith(twitchCompatibility)

    execute {
        try {
            val res = get("res", false)
            // Decoded on request: read the originals before writing next to them.
            val adaptive = get("res/mipmap-anydpi/ic_launcher.xml").readText()
            val foreground = get("res/drawable/ic_launcher_foreground.xml").readText()
            fun write(path: String, text: String) = File(res, path).apply { parentFile.mkdirs() }.writeText(text)

            write("drawable/rt_icon_bg_stars.xml", starsVector())
            // The black outline vanishes on the dark tile: the dark icon takes the brand kit's glitch for dark
            // backgrounds (glitch_flat_purple: purple outline and eyes, white face), as the splash does.
            write("drawable/rt_splash_foreground.xml", foreground.replace("@color/black", "@color/twitch_purple"))
            for ((name, background) in ICONS) {
                var icon = adaptive.replace("@color/twitch_purple", background)
                if (name == "rt_ic_launcher_dark") {
                    icon = icon.replace("@drawable/ic_launcher_foreground\"", "@drawable/rt_splash_foreground\"")
                }
                write("mipmap-anydpi/$name.xml", icon)
            }
            write("drawable/background_splash.xml",
                get("res/drawable/background_splash.xml").readText()
                    .replace("@drawable/ic_launcher_foreground", "@drawable/rt_splash_foreground"))
            document("res/values/styles.xml").use { document ->
                val styles = document.getElementsByTagName("style")
                for (i in 0 until styles.length) {
                    val style = styles.item(i) as Element
                    if (style.getAttribute("name") != "Theme.Twitch.Splash") continue
                    val items = style.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        if (item.getAttribute("name") == "windowSplashScreenBackground") {
                            item.textContent = "@color/hinted_grey_1"
                        }
                    }
                }
            }
            for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
                val png = get("res/mipmap-$density/ic_launcher.png")
                val source = ImageIO.read(png)
                ImageIO.write(recolor(source, stars = true, tileRgb = null), "png", File(png.parentFile, "rt_ic_launcher_stars.png"))
                ImageIO.write(source, "png", File(png.parentFile, "rt_ic_launcher_stock.png"))
                ImageIO.write(recolor(source, stars = false, tileRgb = 0x0E0E10, outlineRgb = 0x9146FF), "png",
                    File(png.parentFile, "rt_ic_launcher_dark.png"))
                ImageIO.write(recolor(source, stars = false, tileRgb = 0x0E0E10), "png",
                    File(png.parentFile, "rt_ic_launcher_black.png"))
            }
            document("AndroidManifest.xml").use { document ->
                val categories = document.getElementsByTagName("category")
                val launcher = (0 until categories.length).map { categories.item(it) as Element }
                    .single { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }
                val activity = launcher.parentNode.parentNode as Element
                launcher.parentNode.removeChild(launcher)
                ICONS.keys.forEachIndexed { index, icon ->
                    val alias = document.createElement("activity-alias")
                    alias.setAttribute("android:name", ALIASES[index])
                    alias.setAttribute("android:targetActivity", activity.getAttribute("android:name"))
                    alias.setAttribute("android:icon", "@mipmap/$icon")
                    alias.setAttribute("android:exported", "true")
                    alias.setAttribute("android:enabled", if (index == 0) "true" else "false")
                    val filter = document.createElement("intent-filter")
                    filter.appendChild(document.createElement("action").apply {
                        setAttribute("android:name", "android.intent.action.MAIN")
                    })
                    filter.appendChild(document.createElement("category").apply {
                        setAttribute("android:name", "android.intent.category.LAUNCHER")
                    })
                    alias.appendChild(filter)
                    activity.parentNode.insertBefore(alias, activity.nextSibling)
                }
            }
            println("[ReTwitch] launcher icons applied")
        } catch (e: Exception) {
            println("[ReTwitch] launcher icons skipped: $e")
        }
        try {
            document("res/drawable/\$splash_glitch_animated__0.xml").use { document ->
                val paths = document.getElementsByTagName("path")
                for (i in 0 until paths.length) {
                    val path = paths.item(i) as Element
                    if (path.getAttribute("android:fillColor").equals("#ff000000", ignoreCase = true)) {
                        path.setAttribute("android:fillColor", "#ff9147ff")
                    }
                }
            }
            document("res/layout/viewer_landing.xml").use { document ->
                val frames = document.getElementsByTagName("FrameLayout")
                for (i in 0 until frames.length) {
                    val frame = frames.item(i) as Element
                    if (frame.getAttribute("android:id") == "@id/splash_overlay") {
                        frame.setAttribute("android:background", "@color/hinted_grey_1")
                    }
                }
                val gradients = document.getElementsByTagName("tv.twitch.android.feature.viewer.landing.SplashGradientView")
                for (i in 0 until gradients.length) {
                    val gradient = gradients.item(i) as Element
                    gradient.setAttribute("android:layout_width", "0.0dip")
                    gradient.setAttribute("android:layout_height", "0.0dip")
                }
            }
            // "ReTwitch" in the wordmark's blocky style: R, e and a capital T drawn here, then
            // Twitch's own "witch" moved right; the image view widens with it.
            document("res/drawable/splash_twitch_wordmark.xml").use { document ->
                val vector = document.documentElement
                val paths = vector.getElementsByTagName("path")
                val kept = (0 until paths.length).map { paths.item(it) as Element }
                kept.first().parentNode.removeChild(kept.first()) // the lowercase "t"
                val group = document.createElement("group")
                group.setAttribute("android:translateX", "10.536")
                for (path in kept.drop(1)) group.appendChild(path)
                fun glyph(data: String) = document.createElement("path").apply {
                    setAttribute("android:fillColor", "#ffffffff")
                    setAttribute("android:fillType", "evenOdd")
                    setAttribute("android:pathData", data)
                }
                vector.appendChild(glyph("M0,0.807H3.5L4.5,1.8V3.6L3.9,4.2L4.5,4.8V7H2.6V5.2H1.984V7H0ZM1.984,2.4H2.6V3.6H1.984Z"))
                vector.appendChild(glyph("M5.992,2.54L5,3.532V6.009L5.992,7H8.97V5.95H6.984V5.35H8.97V2.54ZM6.984,3.5H7.98V4.3H6.984Z"))
                vector.appendChild(glyph("M9.47,0.807H14.47V2.54H12.95V7H10.99V2.54H9.47Z"))
                vector.appendChild(group)
                vector.setAttribute("android:viewportWidth", "38.25")
                vector.setAttribute("android:width", "175.0dip")
            }
            document("res/layout/viewer_landing.xml").use { document ->
                val images = document.getElementsByTagName("ImageView")
                for (i in 0 until images.length) {
                    val image = images.item(i) as Element
                    if (image.getAttribute("android:src") == "@drawable/splash_twitch_wordmark") {
                        image.setAttribute("android:layout_width", "175.0dip")
                    }
                }
            }
            println("[ReTwitch] dark launch cover applied")
        } catch (e: Exception) {
            println("[ReTwitch] dark launch cover skipped: $e")
        }
    }
}

/** Icon mipmap -> adaptive background; same order as [ALIASES] and `AppIcon.ALIASES` in the mod. */
private val ICONS = linkedMapOf(
    "rt_ic_launcher_stars" to "@drawable/rt_icon_bg_stars",
    "rt_ic_launcher_stock" to "@color/twitch_purple",
    "rt_ic_launcher_dark" to "@color/hinted_grey_1",
    "rt_ic_launcher_black" to "@color/hinted_grey_1",
)
private val ALIASES = listOf("tv.retwitch.mod.IconStars", "tv.retwitch.mod.IconStock", "tv.retwitch.mod.IconDark",
    "tv.retwitch.mod.IconBlack")

/** A star of the starry tile, in fractions of the visible (masked) 72dp of the icon. */
private class Star(val x: Double, val y: Double, val r: Double, val alpha: Double, val sparkle: Boolean)

private val STARS: List<Star> = run {
    val random = Random(7)
    val radii = doubleArrayOf(3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
    List(70) {
        Star(random.nextDouble(), random.nextDouble(), radii[random.nextInt(radii.size)] / 1162.75,
            0.4 + random.nextDouble() * 0.6, false)
    } + listOf(Star(0.80, 0.20, 1.0, 1.0, true), Star(0.14, 0.78, 0.8, 1.0, true), Star(0.86, 0.70, 0.7, 1.0, true))
}

private const val STAR_DOT = "#FFFFD9EF"
private const val SPARKLE_GLOW = "#FFB9A4FF"
private const val SPARKLE_RAY = 110 / 1162.75
private const val SPARKLE_WIDTH = 7 / 1162.75

/** The stars tile as a vector in the foreground's viewport, so it lines up with the glitch. */
private fun starsVector(): String {
    val w = 1744.1265
    val h = 1752.5692
    val o = w * 18 / 108
    val s = w * 72 / 108
    fun circle(cx: Double, cy: Double, r: Double) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0Z"
    val paths = StringBuilder()
    fun path(color: String, alpha: Double, data: String) {
        paths.append("<path android:fillColor=\"$color\" android:fillAlpha=\"${"%.2f".format(java.util.Locale.ROOT, alpha)}\" android:pathData=\"$data\"/>\n")
    }
    for (star in STARS) {
        val x = o + star.x * s
        val y = o + star.y * s
        if (star.sparkle) {
            val k = star.r
            val ray = SPARKLE_RAY * s * k
            val width = SPARKLE_WIDTH * s * k
            path(SPARKLE_GLOW, 0.35, circle(x, y, 28 * k))
            path("#FFFFFFFF", 1.0, "M$x,${y - ray}L${x + width},${y}L$x,${y + ray}L${x - width},${y}Z" +
                "M${x - ray},${y}L$x,${y + width}L${x + ray},${y}L$x,${y - width}Z")
            path("#FFFFFFFF", 1.0, circle(x, y, 9 * k))
        } else {
            path(STAR_DOT, star.alpha * 0.3, circle(x, y, star.r * s * 2.2))
            path(STAR_DOT, star.alpha, circle(x, y, star.r * s))
        }
    }
    return """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108.0dip" android:height="108.0dip" android:viewportWidth="$w" android:viewportHeight="$h">
<path android:fillColor="@color/twitch_purple" android:pathData="M0,0H${w}V${h}H0Z"/>
$paths</vector>
"""
}

private const val ICON_PURPLE_R = 144
private const val ICON_PURPLE_G = 69

/**
 * The legacy icon is a blend of purple tile, black glitch and white bubble: each pixel is split into
 * those weights so edges stay smooth. The tile becomes [tileRgb] (or keeps its purple), and with
 * [stars] the starry tile's stars are drawn over the tile part only. The glitch stays black and the
 * tile's soft shadow (black, translucent) stays as it is.
 */
private fun recolor(source: BufferedImage, stars: Boolean, tileRgb: Int?, outlineRgb: Int = 0): BufferedImage {
    val width = source.width
    val height = source.height
    val tile = DoubleArray(width * height)
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            val argb = source.getRGB(x, y)
            val r = argb shr 16 and 0xff
            val g = argb shr 8 and 0xff
            val t = ((r - g) / (ICON_PURPLE_R - ICON_PURPLE_G).toDouble()).coerceIn(0.0, 1.0)
            tile[y * width + x] = t
            if (t > 0.5 && argb ushr 24 == 255) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
    }
    val sky = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    if (stars && maxX > minX) {
        val g = sky.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val size = (maxX - minX).toDouble()
        for (star in STARS) {
            val x = minX + star.x * size
            val y = minY + star.y * (maxY - minY)
            if (star.sparkle) {
                val ray = SPARKLE_RAY * size * star.r
                val w = maxOf(SPARKLE_WIDTH * size * star.r, 0.6)
                g.color = Color(255, 255, 255)
                g.fill(Path2D.Double().apply {
                    moveTo(x, y - ray); lineTo(x + w, y); lineTo(x, y + ray); lineTo(x - w, y); closePath()
                    moveTo(x - ray, y); lineTo(x, y + w); lineTo(x + ray, y); lineTo(x, y - w); closePath()
                })
            } else {
                val r = maxOf(star.r * size, 0.5)
                g.color = Color(255, 217, 239, (star.alpha * 255).toInt())
                g.fill(Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r))
            }
        }
        g.dispose()
    }
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val argb = source.getRGB(x, y)
            val alpha = argb ushr 24
            var r = argb shr 16 and 0xff
            var g = argb shr 8 and 0xff
            var b = argb and 0xff
            val t = tile[y * width + x]
            if (tileRgb != null) {
                val white = ((g - ICON_PURPLE_G * t) / 255).coerceIn(0.0, 1.0)
                // What is neither tile nor white is the black outline and eyes; the tile's soft shadow stays black.
                val black = if (alpha < 255) 0.0 else (1 - t - white).coerceIn(0.0, 1.0)
                fun mix(shift: Int) = (t * (tileRgb shr shift and 0xff) + white * 255 + black * (outlineRgb shr shift and 0xff))
                    .toInt().coerceIn(0, 255)
                r = mix(16); g = mix(8); b = mix(0)
            }
            val star = sky.getRGB(x, y)
            val a = (star ushr 24) / 255.0 * t
            if (a > 0) {
                r = (r + (((star shr 16) and 0xff) - r) * a).toInt()
                g = (g + (((star shr 8) and 0xff) - g) * a).toInt()
                b = (b + ((star and 0xff) - b) * a).toInt()
            }
            out.setRGB(x, y, alpha shl 24 or (r shl 16) or (g shl 8) or b)
        }
    }
    return out
}
