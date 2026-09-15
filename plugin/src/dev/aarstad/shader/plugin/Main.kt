package dev.aarstad.shader.plugin

import android.view.MotionEvent
import dev.aarstad.shader.Plugin

/**
 * The example plugin, in Kotlin.
 *
 * Nothing on the app's side knows this is Kotlin. The boundary is a dex file
 * and a Java interface, so the language on the far side is an implementation
 * detail -- the previous version of this class was Java and it hot-swapped
 * into the same running process this one does.
 *
 * kotlin-stdlib lives in the APK rather than here, so this still pushes as a
 * few KB. `toFloatOrNull` below is the proof it resolves at runtime: it is
 * stdlib, not something the compiler inlines away.
 */
class Main : Plugin {

    private lateinit var host: Plugin.Host

    /** Last touch, already in shader space. */
    private var tx = 0f
    private var ty = 0f
    private var down = false

    /** When the last press landed, on the shader clock. */
    private var tappedAt = -99f

    /** How fast the tap envelope decays; e-folds per second. */
    private var decay = 1.6f

    /** Seconds between automatic preset changes; 0 is off. */
    private var cycleEvery = 0f
    private var cycledAt = 0f

    override fun attach(h: Plugin.Host) {
        host = h
        host.log("touchwarp/kt up, ${host.presetCount()} presets")
        host.toast("plugin: touchwarp (kotlin)")
    }

    override fun frame(t: Float) {
        // Fed every frame regardless of which shader is drawing. Shaders that
        // don't declare these simply never see them.
        host.setUniform("u_touch", tx, ty)
        host.setFloat("u_pulse", Math.exp(-maxOf(0f, t - tappedAt).toDouble() * decay).toFloat())
        host.setFloat("u_down", if (down) 1f else 0f)

        if (cycleEvery > 0f && t - cycledAt >= cycleEvery) {
            cycledAt = t
            val n = host.presetCount()
            if (n > 0) host.select((host.currentPreset() + 1) % n)
        }
    }

    override fun touch(action: Int, x: Float, y: Float): Boolean {
        tx = x
        ty = y
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                tappedAt = host.seconds()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> down = false
        }
        // Not claiming the touch, so tap-to-cycle still works underneath.
        return false
    }

    override fun command(line: String): String {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val verb = parts.firstOrNull().orEmpty()
        val args = parts.drop(1)

        return when (verb) {
            "cycle" -> when {
                args.firstOrNull() == "off" -> {
                    cycleEvery = 0f
                    "auto-cycle off"
                }
                args.isEmpty() -> "cycle ${describeCycle()}"
                else -> args[0].toFloatOrNull()?.let {
                    cycleEvery = it
                    cycledAt = host.seconds()
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

            // Fire the tap envelope without anyone touching the screen.
            "burst" -> {
                if (args.size >= 2) {
                    val x = args[0].toFloatOrNull()
                    val y = args[1].toFloatOrNull()
                    if (x == null || y == null) return "burst wants two numbers in [-1,1], or nothing"
                    tx = x
                    ty = y
                }
                tappedAt = host.seconds()
                host.toast("burst")
                "burst at $tx,$ty"
            }

            "status" -> "touchwarp/kt  touch $tx,$ty  down $down  " +
                "decay $decay  cycle ${describeCycle()}  " +
                "preset ${host.presetName(host.currentPreset())}"

            else -> "commands: cycle <s>|off, decay <n>, burst [x y], status"
        }
    }

    private fun describeCycle() = if (cycleEvery > 0f) "${cycleEvery}s" else "off"

    override fun detach() {
        host.log("touchwarp/kt down")
    }
}
