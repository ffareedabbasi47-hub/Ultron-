package com.ultron.ai.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class AvatarMood { IDLE, LISTENING, THINKING, SPEAKING }

class SMat(
    var base: FloatArray, val metal: Float, val rough: Float,
    var em: FloatArray = floatArrayOf(0f, 0f, 0f), var ei: Float = 0f, val det: Float = 0f,
)

class SNode(
    val name: String,
    var t: FloatArray = floatArrayOf(0f, 0f, 0f),
    var r: FloatArray = floatArrayOf(0f, 0f, 0f),
    var s: FloatArray = floatArrayOf(1f, 1f, 1f),
    val mesh: Mesh? = null,
    val mat: SMat? = null,
    val hud: Boolean = false,
) {
    val children = ArrayList<SNode>()
    var world: FloatArray = Mat4.identity()
    var bx = 0f
}

private fun hex(h: Int): FloatArray = floatArrayOf(
    (((h shr 16) and 255) / 255f).pow(2.2f), (((h shr 8) and 255) / 255f).pow(2.2f), ((h and 255) / 255f).pow(2.2f),
)

private object Mats {
    fun gun() = SMat(hex(0x3b3d52), .95f, .34f, det = 1f)
    fun steel() = SMat(hex(0x9a9fb6), 1f, .24f, det = 1f)
    fun dark() = SMat(hex(0x0b0b12), .5f, .55f)
    fun trim() = SMat(hex(0x3a2370), .9f, .3f, hex(0x8b5cf6), .18f, .6f)
    fun red() = SMat(hex(0xff2a44), 0f, .4f, hex(0xff2a44), 3.2f)
    fun white() = SMat(hex(0xffd6dc), 0f, .4f, hex(0xffe3e6), 5f)
    fun glow() = SMat(hex(0x8b5cf6), 0f, .4f, hex(0x8b5cf6), 2.2f)
    fun ring() = SMat(hex(0x8b5cf6), 0f, .4f, hex(0x8b5cf6), 1.6f)
}

private fun damp(a: Float, b: Float, k: Float, dt: Float): Float = a + (b - a) * (1f - exp(-k * dt))
private fun clamp(x: Float, a: Float, b: Float) = max(a, min(b, x))

/**
 * Original robotic ULTRON bust. Same design as the browser avatar (ultron-avatar.html) and its GLB export,
 * expressed as a scene graph of procedural meshes. Not derived from any film asset.
 */
class UltronScene(reducedMotion: Boolean = false) {
    val nodes = ArrayList<SNode>()
    val root = add(SNode("Root"), null)
    private val bust = add(SNode("Bust"), root)
    private val head = add(SNode("Head", t = floatArrayOf(0f, -.48f, -.02f)), root)
    private val jaw: SNode
    private val core: SNode
    private val sensors = ArrayList<SNode>()
    private val eyes = ArrayList<SNode>()
    private val eyeMat = Mats.red()
    private val rings = ArrayList<SNode>()
    var reduced = reducedMotion

    // animation state
    var mood = AvatarMood.IDLE
    var lookX = 0f; var lookY = 0f
    private var lookTX = 0f; private var lookTY = 0f
    private val accent = floatArrayOf(.55f, .36f, .96f)
    private var mouth = 0f
    private var blink = 0f
    private var nextBlink = 3f
    private var time = 0f
    val eyeIntensity get() = eyeMat.ei
    val accentColor: FloatArray get() = accent

    private fun add(n: SNode, parent: SNode?): SNode { parent?.children?.add(n); nodes.add(n); return n }
    private fun n(
        name: String, parent: SNode, t: FloatArray = floatArrayOf(0f, 0f, 0f), r: FloatArray = floatArrayOf(0f, 0f, 0f),
        mesh: Mesh? = null, mat: SMat? = null, hud: Boolean = false,
    ) = add(SNode(name, t.copyOf(), r.copyOf(), mesh = mesh, mat = mat, hud = hud), parent)

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private val HP = PI.toFloat()

    init {
        // bust
        n("Chest", bust, v(0f, -1.58f, 0f), mesh = Geometry.superE(1.25f, .58f, .72f, .45f, .45f), mat = Mats.gun())
        n("ChestPlate", bust, v(0f, -1.42f, .52f), mesh = Geometry.superE(.82f, .36f, .22f, .5f, .5f), mat = Mats.steel())
        n("ReactorRing", bust, v(0f, -1.5f, .77f), v(HP / 2, 0f, 0f), Geometry.torus(.27f, .045f), Mats.steel())
        core = n("Core", bust, v(0f, -1.5f, .78f), v(HP / 2, 0f, 0f), Geometry.cyl(.2f, .2f, .06f, 6), Mats.glow())
        n("CollarBase", bust, v(0f, -1.02f, 0f), mesh = Geometry.cyl(.36f, .54f, .26f, 40), mat = Mats.dark())
        for (sx in floatArrayOf(-1f, 1f)) {
            val side = if (sx < 0) "L" else "R"
            n("Shoulder$side", bust, v(sx * 1.17f, -1.22f, -.04f), mesh = Geometry.superE(.42f, .3f, .4f, .6f, .7f), mat = Mats.gun())
            n("ShoulderTrim$side", bust, v(sx * 1.17f, -1.22f, -.04f), v(0f, 0f, HP / 2), Geometry.torus(.36f, .018f), Mats.trim())
        }
        // neck
        n("Neck", root, v(0f, -.8f, -.02f), mesh = Geometry.cyl(.23f, .25f, .62f, 32), mat = Mats.dark())
        for (i in 0 until 3) {
            val a = (i - 1) * .5f
            n("Cable$i", root, v(sin(a) * .2f, -.78f, .18f + cos(a) * .04f), v(.12f, 0f, -a * .25f), Geometry.cyl(.03f, .03f, .7f, 10), Mats.steel())
        }
        // head
        n("Skull", head, v(0f, .5f, -.05f), mesh = Geometry.superE(.52f, .6f, .58f, .62f, .72f, 56, 32), mat = Mats.gun())
        n("CrownPlate", head, v(0f, .8f, .12f), v(.55f, 0f, 0f), Geometry.superE(.4f, .07f, .4f, .5f, .5f, 32, 16), Mats.steel())
        n("Crest", head, v(0f, 1.02f, -.08f), v(.12f, 0f, 0f), Geometry.box(.06f, .12f, .78f), Mats.steel())
        n("CrestGlow", head, v(0f, 1.085f, -.08f), v(.12f, 0f, 0f), Geometry.box(.016f, .012f, .6f), Mats.glow())
        n("Faceplate", head, v(0f, .4f, .34f), mesh = Geometry.superE(.43f, .5f, .3f, .5f, .55f, 48, 28), mat = Mats.steel())
        n("ForeheadPlate", head, v(0f, .76f, .46f), v(.3f, 0f, 0f), Geometry.superE(.34f, .14f, .12f, .5f, .5f, 32, 16), Mats.gun())
        n("Brow", head, v(0f, .67f, .52f), v(.42f, 0f, 0f), Geometry.box(.96f, .12f, .36f), Mats.gun())
        n("BrowEdge", head, v(0f, .605f, .66f), v(.42f, 0f, 0f), Geometry.box(.9f, .012f, .04f), Mats.trim())
        n("VisorRecess", head, v(0f, .5f, .6f), mesh = Geometry.box(.82f, .2f, .1f), mat = Mats.dark())
        n("NoseBridge", head, v(0f, .33f, .62f), v(-.1f, 0f, 0f), Geometry.box(.1f, .3f, .09f), Mats.steel())
        for (sx in floatArrayOf(-1f, 1f)) {
            val e = n("Eye" + if (sx < 0) "L" else "R", head, v(sx * .2f, .5f, .655f), v(0f, 0f, sx * .24f), Geometry.box(.27f, .042f, .03f), eyeMat)
            e.bx = sx * .2f
            eyes.add(e)
        }
        for (sx in floatArrayOf(-1f, 1f)) {
            val side = if (sx < 0) "L" else "R"
            sensors.add(n("Sensor$side", head, v(sx * .47f, .5f, .28f), v(0f, 0f, HP / 2), Geometry.cyl(.045f, .045f, .1f, 16), Mats.glow()))
            n("Cheek$side", head, v(sx * .37f, .2f, .4f), v(0f, sx * -.25f, sx * -.2f), Geometry.superE(.14f, .2f, .17f, .5f, .5f, 28, 16), Mats.steel())
            n("CheekVent$side", head, v(sx * .4f, .16f, .55f), v(0f, sx * -.5f, 0f), Geometry.box(.012f, .15f, .04f), Mats.dark())
            n("Fin$side", head, v(sx * .57f, .66f, -.12f), v(0f, sx * .5f, sx * .28f), Geometry.box(.035f, .55f, .36f), Mats.trim())
            n("FinEdge$side", head, v(sx * .585f, .66f, .05f), v(0f, sx * .5f, sx * .28f), Geometry.box(.012f, .5f, .02f), Mats.glow())
            n("Ear$side", head, v(sx * .54f, .38f, 0f), v(0f, 0f, HP / 2), Geometry.cyl(.13f, .13f, .08f, 28), Mats.steel())
            n("EarCap$side", head, v(sx * .585f, .38f, 0f), v(0f, 0f, HP / 2), Geometry.cyl(.07f, .07f, .02f, 20), Mats.dark())
        }
        jaw = n("Jaw", head, v(0f, .14f, .04f))
        n("JawPlate", jaw, v(0f, -.08f, .27f), mesh = Geometry.superE(.34f, .13f, .3f, .5f, .55f, 36, 20), mat = Mats.gun())
        n("MouthRecess", jaw, v(0f, -.02f, .545f), mesh = Geometry.box(.3f, .1f, .06f), mat = Mats.dark())
        for (i in -4..4) n("Slat${i + 4}", jaw, v(i * .034f, -.02f, .578f), mesh = Geometry.box(.012f, .085f, .012f), mat = Mats.steel())
        n("Chin", jaw, v(0f, -.2f, .47f), mesh = Geometry.box(.17f, .06f, .08f), mat = Mats.steel())
        // HUD rings
        listOf(Triple(1.0f, .012f, Mats::ring), Triple(1.2f, .009f, Mats::glow), Triple(1.4f, .007f, Mats::ring)).forEachIndexed { i, (rr, tt, mk) ->
            rings.add(n("HudRing$i", root, v(0f, 0f, -.85f), v(HP / 2, 0f, 0f), Geometry.torus(rr, tt, 128, 6), mk(), hud = true))
        }
        updateWorld()
    }

    private fun stateColor(m: AvatarMood) = when (m) {
        AvatarMood.IDLE -> floatArrayOf(.55f, .36f, .96f)
        AvatarMood.LISTENING -> floatArrayOf(.13f, .83f, .93f)
        AvatarMood.THINKING -> floatArrayOf(.98f, .75f, .14f)
        AvatarMood.SPEAKING -> floatArrayOf(1f, .16f, .27f)
    }

    /** Advances the animation by [dt] seconds. Mouth motion while speaking is approximate (no TTS amplitude on Android). */
    fun animate(dt: Float) {
        time += dt
        val t = time
        val mo = if (reduced) 0f else 1f
        val col = stateColor(mood)
        for (i in 0..2) accent[i] = damp(accent[i], col[i], 5f, dt)
        val speaking = mood == AvatarMood.SPEAKING
        val thinking = mood == AvatarMood.THINKING
        val listening = mood == AvatarMood.LISTENING
        val mouthT = if (speaking) clamp(.18f + .25f * abs(sin(t * 13f)) * mo + .3f * mo, 0f, 1f) else 0f
        mouth = damp(mouth, mouthT, 18f, dt)
        val tx = if (thinking) sin(t * .9f) * .6f * mo else lookX
        val ty = if (thinking) .25f else lookY
        lookTX = damp(lookTX, tx, 4f, dt); lookTY = damp(lookTY, ty, 4f, dt)
        val nod = if (speaking) mouth * .05f * mo else 0f
        head.r = floatArrayOf(-lookTY * .22f + nod + (if (listening) -.05f else 0f), lookTX * .42f, if (thinking) .07f * sin(t * .9f) * mo else 0f)
        head.t = floatArrayOf(0f, -.48f + sin(t * 1.5f) * .006f * mo, -.02f)
        bust.s = floatArrayOf(1f, 1f + sin(t * 1.5f) * .006f * mo, 1f)
        jaw.r = floatArrayOf(mouth * .34f, 0f, 0f)

        nextBlink -= dt
        if (nextBlink <= 0f && !reduced) { blink = .16f; nextBlink = 3f + (t * 7.13f % 3f) }
        blink = max(0f, blink - dt)
        val bl = if (blink > 0f) sin(blink / .16f * PI.toFloat()) else 0f
        val speakGlow = if (speaking) .35f + .9f * mouth else 0f
        eyeMat.ei = 2.6f + (if (mood == AvatarMood.IDLE) .5f * sin(t * 1.3f) * mo else 0f) + speakGlow * 2f + (if (thinking) 1.4f else 0f)
        val scan = if (thinking) sin(t * 3.2f) * .5f else lookTX * .4f
        val narrow = if (thinking) .65f + .35f * sin(t * 9f) * mo else if (listening) 1.25f else 1f
        for (e in eyes) {
            e.s = floatArrayOf((.96f + .04f * sin(t * 1.7f) * mo), max(.1f, narrow * (1f - bl * .92f)), 1f)
            e.t = floatArrayOf(e.bx + scan * .05f, .5f, .655f)
        }
        for (n in sensors + core + rings) { val m = n.mat!!; m.em = accent.copyOf(); m.base = accent.copyOf() }
        core.mat!!.ei = 1.4f + .7f * sin(t * 2.2f) * mo + (if (speaking) mouth * 2f else 0f)
        for (s in sensors) s.mat!!.ei = 1.5f + (if (thinking) sin(t * 10f) * .8f else 0f)
        rings.forEachIndexed { i, r ->
            r.r = floatArrayOf(HP / 2, 0f, t * (.12f + i * .07f) * (if (i % 2 == 1) -1f else 1f) * mo)
            r.mat!!.ei = .9f + (if (thinking) .6f + .4f * sin(t * 5f + i) else 0f)
        }
        updateWorld()
    }

    fun updateWorld() = updateWorld(root, null)

    private fun updateWorld(n: SNode, parent: FloatArray?) {
        val loc = Mat4.trs(n.t, Mat4.quatEuler(n.r[0], n.r[1], n.r[2]), n.s)
        n.world = if (parent != null) Mat4.mul(parent, loc) else loc
        for (c in n.children) updateWorld(c, n.world)
    }

    /** World-space light position near the face (follows head motion). */
    fun lightPosition(): FloatArray = Mat4.point(head.world, floatArrayOf(0f, .5f, .9f))
}
