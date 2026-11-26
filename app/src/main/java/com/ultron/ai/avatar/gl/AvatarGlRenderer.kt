package com.ultron.ai.avatar.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.ultron.ai.avatar.AvatarMood
import com.ultron.ai.avatar.Mat4
import com.ultron.ai.avatar.SNode
import com.ultron.ai.avatar.UltronScene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

private const val TAG = "UltronGl"

private const val VS = """#version 300 es
layout(location=0) in vec3 aP; layout(location=1) in vec3 aN;
uniform mat4 uM,uVP; uniform mat3 uNM;
out vec3 vW,vN,vL;
void main(){vec4 w=uM*vec4(aP,1.);vW=w.xyz;vL=aP;vN=normalize(uNM*aN);gl_Position=uVP*w;}"""

private const val FS = """#version 300 es
precision highp float;
in vec3 vW,vN,vL; out vec4 o;
uniform vec3 uCam,uBase,uEm,uLp,uLc,uAccent; uniform float uMetal,uRough,uEi,uLi,uDet;
float h3(vec3 p){return fract(sin(dot(p,vec3(127.1,311.7,74.7)))*43758.5453);}
float vn(vec3 p){vec3 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
  return mix(mix(mix(h3(i),h3(i+vec3(1,0,0)),f.x),mix(h3(i+vec3(0,1,0)),h3(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(h3(i+vec3(0,0,1)),h3(i+vec3(1,0,1)),f.x),mix(h3(i+vec3(0,1,1)),h3(i+vec3(1,1,1)),f.x),f.y),f.z);}
vec3 env(vec3 d,float rg){
  float y=d.y;
  vec3 c=mix(vec3(.012,.012,.02),vec3(.07,.07,.12),smoothstep(-.4,1.,y));
  c+=vec3(1.6,1.5,1.45)*pow(max(dot(d,normalize(vec3(-.5,.75,.55))),0.),mix(90.,5.,rg))*1.6;
  c+=vec3(1.3,1.3,1.5)*smoothstep(.93,.99,dot(d,normalize(vec3(.1,.95,.2))))*(1.-rg)*1.3;
  c+=uAccent*pow(max(dot(d,normalize(vec3(.85,.25,-.5))),0.),mix(60.,4.,rg))*2.2;
  c+=vec3(.1,.8,.95)*pow(max(dot(d,normalize(vec3(-.9,.05,-.35))),0.),mix(60.,4.,rg))*1.4;
  c+=vec3(1.,.12,.2)*.12*pow(max(d.z,0.),3.);
  c+=vec3(.35,.3,.5)*pow(max(-d.y,0.),2.)*.2;
  return c;
}
vec3 aces(vec3 x){return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.,1.);}
void main(){
  vec3 N=normalize(vN),V=normalize(uCam-vW);
  float br=vn(vL*vec3(110.,7.,110.)),gr=vn(vL*16.),sc=vn(vL*vec3(260.,3.,40.));
  float s1=abs(fract(vL.y*2.3+vL.x*.4)-.5)*2.;
  float seam=smoothstep(.985,.996,s1)*.8*uDet;
  float rough=clamp(uRough+uDet*((br-.5)*.26+(gr-.5)*.16+step(.93,sc)*.25)+seam*.3,.05,1.);
  vec3 base=uBase*(1.+uDet*((gr-.5)*.4-.06))*(1.-seam*.75);
  N=normalize(N+uDet*.06*vec3(br-.5,gr-.5,sc-.5));
  vec3 R=reflect(-V,N);float nv=max(dot(N,V),0.);
  vec3 f0=mix(vec3(.04),base,uMetal);
  vec3 F=f0+(max(vec3(1.-rough),f0)-f0)*pow(1.-nv,5.);
  float ao=mix(.5,1.,smoothstep(-1.,.35,N.y))*(1.-seam*.5);
  vec3 spec=env(R,rough)*F*ao;
  vec3 diff=base*(1.-uMetal)*(env(N,1.)*.55+.04)*ao;
  vec3 L=uLp-vW;float d2=dot(L,L);L=normalize(L);
  diff+=base*(1.-uMetal)*uLc*uLi*max(dot(N,L),0.)/(1.+d2*2.);
  spec+=uLc*uLi*pow(max(dot(R,L),0.),mix(90.,6.,rough))*mix(.2,1.,uMetal)*f0*1.3/(1.+d2*2.);
  float rim=pow(1.-nv,3.5);
  vec3 col=diff+spec+uEm*uEi+uAccent*rim*.16*ao;
  o=vec4(pow(aces(col),vec3(1./2.2)),1.);
}"""

private const val BG_VS = """#version 300 es
out vec2 vUv;void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));vUv=p;gl_Position=vec4(p*2.-1.,0.,1.);}"""

private const val BG_FS = """#version 300 es
precision highp float;in vec2 vUv;out vec4 o;
uniform vec3 uAccent;uniform float uT,uAsp;
float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
void main(){
  vec2 p=vUv-vec2(.5,.62);p.x*=uAsp;float r=length(p);
  vec3 bg=mix(vec3(.11,.06,.22),vec3(.018,.014,.035),smoothstep(0.,.95,r));
  bg+=uAccent*.05*smoothstep(.55,.0,abs(r-.42));
  vec2 g=floor(vUv*vec2(uAsp*90.,90.));float st=h(g);
  bg+=vec3(.6,.7,1.)*step(.9965,st)*(.4+.6*sin(uT*1.5+st*40.))*.5;
  bg*=1.-.4*smoothstep(.45,1.,r);
  o=vec4(bg,1.);}"""

/**
 * Native OpenGL ES 3.0 renderer for the built-in ULTRON avatar (used by the live wallpaper).
 * Simplified versus the WebGL viewer: no MSAA and no bloom, to keep battery use low.
 * Every GL call happens on the GLSurfaceView render thread; public setters are thread-safe via @Volatile.
 */
class AvatarGlRenderer(private val lowPower: Boolean = false) : GLSurfaceView.Renderer {
    @Volatile var mood: AvatarMood = AvatarMood.IDLE
    @Volatile var lookX = 0f
    @Volatile var lookY = 0f
    @Volatile var yaw = 0f
    @Volatile var reducedMotion = false
    @Volatile var failed = false
        private set

    private class Gpu(val vao: Int, val count: Int, val buffers: IntArray)

    private var scene: UltronScene? = null
    private val gpu = HashMap<SNode, Gpu>()
    private var prog = 0
    private var bgProg = 0
    private var bgVao = 0
    private val u = HashMap<String, Int>()
    private val bu = HashMap<String, Int>()
    private var w = 1
    private var h = 1
    private var last = 0L
    private var clock = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            release()
            prog = link(VS, FS)
            bgProg = link(BG_VS, BG_FS)
            for (n in listOf("uM", "uVP", "uNM", "uCam", "uBase", "uEm", "uLp", "uLc", "uAccent", "uMetal", "uRough", "uEi", "uLi", "uDet"))
                u[n] = GLES30.glGetUniformLocation(prog, n)
            for (n in listOf("uAccent", "uT", "uAsp")) bu[n] = GLES30.glGetUniformLocation(bgProg, n)
            val tmp = IntArray(1)
            GLES30.glGenVertexArrays(1, tmp, 0); bgVao = tmp[0]
            val s = UltronScene(reducedMotion)
            scene = s
            for (n in s.nodes) { val m = n.mesh ?: continue; gpu[n] = upload(m.p, m.n, m.idx) }
            last = 0L
            failed = false
        } catch (e: RuntimeException) {
            Log.e(TAG, "GL init failed: ${e.message}")
            failed = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = maxOf(1, width); h = maxOf(1, height)
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = scene
        if (failed || s == null) { GLES30.glClearColor(.03f, .02f, .06f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }
        val now = System.nanoTime()
        val dt = if (last == 0L) 1f / 30f else min(.05f, (now - last) / 1e9f)
        last = now; clock += dt
        s.mood = mood; s.lookX = lookX; s.lookY = lookY; s.reduced = reducedMotion
        s.animate(dt)

        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val asp = w.toFloat() / h
        GLES30.glUseProgram(bgProg)
        val acc = s.accentColor
        GLES30.glUniform3f(bu["uAccent"]!!, acc[0], acc[1], acc[2]); GLES30.glUniform1f(bu["uT"]!!, clock); GLES30.glUniform1f(bu["uAsp"]!!, asp)
        GLES30.glBindVertexArray(bgVao); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        val portrait = asp < .85f
        val dist = 5.6f * (if (portrait) 1.5f else 1.1f) / min(1f, asp * 1.15f + .25f)
        val pitch = -.08f
        val yw = yaw // faces the viewer
        val eye = floatArrayOf(sin(yw) * cos(pitch) * dist, .2f + sin(pitch) * dist, cos(yw) * cos(pitch) * dist)
        val target = floatArrayOf(0f, if (portrait) -1.1f else -.8f, 0f)
        val vp = Mat4.mul(Mat4.persp(Math.toRadians(32.0).toFloat(), asp, .1f, 50f), Mat4.lookAt(eye, target, floatArrayOf(0f, 1f, 0f)))
        val lp = s.lightPosition()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDepthFunc(GLES30.GL_LEQUAL); GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(u["uVP"]!!, 1, false, vp, 0)
        GLES30.glUniform3fv(u["uCam"]!!, 1, eye, 0)
        GLES30.glUniform3fv(u["uAccent"]!!, 1, acc, 0)
        GLES30.glUniform3fv(u["uLp"]!!, 1, lp, 0)
        GLES30.glUniform3f(u["uLc"]!!, 1f, .3f, .35f)
        GLES30.glUniform1f(u["uLi"]!!, .9f + s.eyeIntensity * .12f)
        for (n in s.nodes) {
            val g = gpu[n] ?: continue
            val m = n.mat ?: continue
            GLES30.glUniformMatrix4fv(u["uM"]!!, 1, false, n.world, 0)
            GLES30.glUniformMatrix3fv(u["uNM"]!!, 1, false, Mat4.normalMat(n.world), 0)
            GLES30.glUniform3fv(u["uBase"]!!, 1, m.base, 0)
            GLES30.glUniform3fv(u["uEm"]!!, 1, m.em, 0)
            GLES30.glUniform1f(u["uEi"]!!, m.ei)
            GLES30.glUniform1f(u["uMetal"]!!, m.metal)
            GLES30.glUniform1f(u["uRough"]!!, m.rough)
            GLES30.glUniform1f(u["uDet"]!!, m.det)
            GLES30.glBindVertexArray(g.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.count, GLES30.GL_UNSIGNED_INT, 0)
        }
        GLES30.glBindVertexArray(0)
    }

    /** Frees GPU objects; also called when the GL context is recreated. */
    fun release() {
        for (g in gpu.values) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(g.vao), 0)
            GLES30.glDeleteBuffers(g.buffers.size, g.buffers, 0)
        }
        gpu.clear()
        if (prog != 0) GLES30.glDeleteProgram(prog)
        if (bgProg != 0) GLES30.glDeleteProgram(bgProg)
        if (bgVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(bgVao), 0)
        prog = 0; bgProg = 0; bgVao = 0
    }

    private fun upload(p: FloatArray, n: FloatArray, idx: IntArray): Gpu {
        val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
        val bufs = IntArray(3); GLES30.glGenBuffers(3, bufs, 0)
        fun fbuf(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.size * 4, fbuf(p), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n.size * 4, fbuf(n), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)
        val ib: IntBuffer = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(idx).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufs[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        return Gpu(vao[0], idx.size, bufs)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(s); GLES30.glDeleteShader(s); throw RuntimeException("shader: $log") }
            return s
        }
        val p = GLES30.glCreateProgram()
        val v = compile(GLES30.GL_VERTEX_SHADER, vs); val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetProgramInfoLog(p); GLES30.glDeleteProgram(p); throw RuntimeException("link: $log") }
        return p
    }
}
