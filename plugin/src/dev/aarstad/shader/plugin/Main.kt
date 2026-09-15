package dev.aarstad.shader.plugin

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

import dev.aarstad.shader.gl.Gl
import dev.aarstad.shader.host.Plugin
import dev.aarstad.shader.host.PluginScreen
import dev.aarstad.shader.host.PluginService

/**
 * The example plugin: everything here is pushed as dex, so any of it changes
 * without a reinstall.
 */
class Main : Plugin {

    private lateinit var host: Plugin.Host

    /** Null in a host that isn't drawing shaders. Everything GL is guarded on it. */
    private var gl: Gl? = null

    private var panel: View? = null
    private var label: TextView? = null
    private var shownPreset = -1

    private var frames = 0
    private var lastFrames = 0
    private var lastAt = 0f
    private var fps = 0f

    /** Last touch, already in shader space. */
    private var tx = 0f
    private var ty = 0f
    private var down = false

    private var tappedAt = -99f
    private var decay = 1.6f
    private var cycleEvery = 0f
    private var cycledAt = 0f

    override fun attach(h: Plugin.Host) {
        host = h
        gl = h.extension("gl") as? Gl

        // Survives a swap: the host holds it, not this instance, so it counts every push rather than resetting to 1 each time.
        val swaps = host.state().getInt("swaps") + 1
        host.state().putInt("swaps", swaps)

        buildUi(swaps)
        gl?.onFrame { t -> frame(t) }

        host.log("touchwarp/ui up, swap #$swaps, gl=${gl != null}")
        host.toast("plugin: touchwarp (ui)")
    }

    override fun detach() {
        // The host empties the container and drops the frame callback itself, so there is nothing to undo here beyond letting go.
        panel = null
        label = null
        host.log("touchwarp/ui down")
    }

    private fun buildUi(swaps: Int) {
        val ctx = host.activity()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val caption = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }

        val next = Button(ctx).apply {
            text = "next"
            setOnClickListener { cycle() }
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xC0101014.toInt())
                cornerRadius = dp(22).toFloat()
            }
            addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(next)
        }

        // Clear of the gesture bar at the bottom and the cutout at the top.
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(20), 0, dp(20), dp(56))
        }

        host.container().addView(row, lp)
        panel = row
        label = caption
        refresh(swaps)
    }

    private fun refresh(swaps: Int = host.state().getInt("swaps")) {
        val g = gl
        val where = if (g == null) "no gl" else "${g.presetName(g.currentPreset())}"
        label?.text = "$where   ·   swap #$swaps"
    }

    private fun cycle() {
        val g = gl ?: return
        val n = g.presetCount()
        if (n > 0) g.select((g.currentPreset() + 1) % n)
    }

    /** GL thread, with the drawing program bound. */
    private fun frame(t: Float) {
        val g = gl ?: return
        frames++

        // Measured over a one-second window, so "is it slow" has an answer.
        if (t - lastAt >= 1f) {
            fps = (frames - lastFrames) / (t - lastAt)
            lastFrames = frames
            lastAt = t
        }

        g.setUniform("u_touch", tx, ty)
        g.setFloat("u_pulse", Math.exp(-maxOf(0f, t - tappedAt).toDouble() * decay).toFloat())
        g.setFloat("u_down", if (down) 1f else 0f)

        if (cycleEvery > 0f && t - cycledAt >= cycleEvery) {
            cycledAt = t
            val n = g.presetCount()
            if (n > 0) g.select((g.currentPreset() + 1) % n)
        }

        // Only touch the UI when the preset actually changed -- this runs 60 times a second and the label does not.
        val current = g.currentPreset()
        if (current != shownPreset) {
            shownPreset = current
            host.post { refresh() }
        }
    }

    override fun event(name: String, vararg args: Any?): Boolean = when (name) {
        Plugin.TOUCH -> {
            val action = args[0] as Int
            tx = args[1] as Float
            ty = args[2] as Float
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    down = true
                    tappedAt = gl?.seconds() ?: 0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> down = false
            }
            // Not claiming it, so tap-to-cycle still works underneath.
            false
        }

        // First back press hides the panel; a second one leaves the app, which is what returning false asks the host to do.
        Plugin.BACK -> {
            val p = panel
            if (p != null && p.visibility == View.VISIBLE) {
                p.visibility = View.GONE
                true
            } else {
                false
            }
        }

        Plugin.RESUME -> {
            host.log("resumed")
            false
        }

        // The spare activity handed us its container to fill.
        Plugin.SCREEN_OPEN -> {
            val root = args[0] as ViewGroup
            val ctx = host.activity()
            root.addView(TextView(ctx).apply {
                text = "second screen, built by pushed code\n\n" +
                    "swap #${host.state().getInt("swaps")}\n" +
                    "files: ${host.dataDir().list()?.size ?: 0}"
                setPadding(48, 96, 48, 48)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF101014.toInt())
                textSize = 16f
            })
            true
        }

        Plugin.SERVICE_START -> {
            host.log("service started: ${args.getOrNull(0)}")
            true
        }

        Plugin.SERVICE_STOP -> {
            host.log("service stopped")
            true
        }

        // Unknown names must be ignored, so a plugin keeps working against a host that sends more than it knows about.
        else -> false
    }

    override fun command(line: String): String {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val verb = parts.firstOrNull().orEmpty()
        val args = parts.drop(1)

        return when (verb) {
            "screen" -> {
                host.post { PluginScreen.open(host.activity()) }
                "opening the second screen"
            }

            "service" -> {
                val on = args.firstOrNull() != "off"
                host.post {
                    if (on) PluginService.start(host.activity(), "touchwarp")
                    else PluginService.stop(host.activity())
                }
                "service ${if (on) "on" else "off"}"
            }

            "files" -> host.dataDir().listFiles()
                ?.joinToString("\n") { "${it.length()}  ${it.name}" }
                ?.ifEmpty { "no files" }
                ?: "no files"

            "ui" -> {
                val p = panel ?: return "no panel"
                val show = args.firstOrNull() != "off"
                host.post { p.visibility = if (show) View.VISIBLE else View.GONE }
                "ui ${if (show) "on" else "off"}"
            }

            "cycle" -> when {
                args.firstOrNull() == "off" -> {
                    cycleEvery = 0f
                    "auto-cycle off"
                }
                args.isEmpty() -> "cycle ${describeCycle()}"
                else -> args[0].toFloatOrNull()?.let {
                    cycleEvery = it
                    cycledAt = gl?.seconds() ?: 0f
                    "auto-cycle every ${it}s"
                } ?: "cycle wants seconds, or 'off'"
            }

            "decay" -> when {
                args.isEmpty() -> "decay $decay"
                else -> args[0].toFloatOrNull()?.let {
                    decay = it
                    "decay $it"
                } ?: "decay wants a number"
            }

            "burst" -> {
                if (args.size >= 2) {
                    val x = args[0].toFloatOrNull()
                    val y = args[1].toFloatOrNull()
                    if (x == null || y == null) return "burst wants two numbers in [-1,1], or nothing"
                    tx = x
                    ty = y
                }
                tappedAt = gl?.seconds() ?: 0f
                host.toast("burst")
                "burst at $tx,$ty"
            }

            "status" -> "touchwarp/ui  fps=${"%.1f".format(fps)}  frames=$frames  " +
                "swap #${host.state().getInt("swaps")}  " +
                "gl=${gl != null}  touch $tx,$ty  down $down  " +
                "decay $decay  cycle ${describeCycle()}  " +
                "panel=${if (panel?.visibility == View.VISIBLE) "shown" else "hidden"}"

            else -> "commands: ui on|off, cycle <s>|off, decay <n>, burst [x y], " +
                "screen, service on|off, files, status"
        }
    }

    private fun describeCycle() = if (cycleEvery > 0f) "${cycleEvery}s" else "off"
}
