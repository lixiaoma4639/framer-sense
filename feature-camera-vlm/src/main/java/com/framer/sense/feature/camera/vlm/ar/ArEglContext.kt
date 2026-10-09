package com.framer.sense.feature.camera.vlm.ar

import android.opengl.EGL14
import android.opengl.EGLConfig

/** ARCore 的 GL 纹理与 Filament 共享；所有调用在 AR 专用线程上执行。 */
internal class ArEglContext : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val context: android.opengl.EGLContext
    private val surface: android.opengl.EGLSurface
    init {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE), 0,
            configs, 0, 1, IntArray(1), 0))
        val config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        surface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE)
        makeCurrent()
    }
    fun makeCurrent() { check(EGL14.eglMakeCurrent(display, surface, surface, context)) }
    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context)
        // EGL display 由进程内多个 Filament 实例共享，不 eglTerminate 其他渲染器的 display。
    }
}
