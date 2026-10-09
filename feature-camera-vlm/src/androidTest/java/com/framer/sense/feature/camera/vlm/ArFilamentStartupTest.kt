package com.framer.sense.feature.camera.vlm

import android.opengl.GLES11Ext
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.ar.ArEglContext
import com.framer.sense.feature.camera.vlm.ar.ArFilamentScene
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** 单独运行本类、使用新测试进程，覆盖未打开样片时直接启动 AR 渲染器的 JNI 初始化。 */
@RunWith(AndroidJUnit4::class)
class ArFilamentStartupTest {
    @Test fun sceneInitializesNativeLibrariesBeforeAnyEntityAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val startup = FutureTask<Unit> {
            // 此处故意不提前调用 Filament.init()，否则会掩盖渲染器的初始化顺序错误。
            ArEglContext().use { egl ->
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                try {
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    ArFilamentScene(context, egl, textures[0]).use { }
                } finally {
                    egl.makeCurrent()
                    GLES20.glDeleteTextures(1, textures, 0)
                }
            }
        }
        val thread = Thread(startup, "vlm-ar-startup-test")
        thread.start()
        try { startup.get(60, TimeUnit.SECONDS) }
        finally { thread.interrupt() }
    }
}
