// ============================================================================
//  GRADE EFFECT — noi engine mau vao pipeline video cua Media3
//  GlEffect  : mo ta hieu ung, Media3 goi toGlShaderProgram() tren GL thread.
//  BaseGlShaderProgram : moi frame goi drawFrame(inputTexId, ptsUs).
//  Tham so lay tu GradeBus (UI ghi, GL doc) nen keo slider la thay ngay,
//  ke ca khi video dang TAM DUNG — Media3 ve lai frame hien tai.
//
//  LUT 3D: GlProgram cua Media3 chi ho tro sampler 2D, nen texture 3D duoc
//  tao/gan bang GLES30 truc tiep, roi set uniform qua glUniform1i.
// ============================================================================
package com.freedive.colorapp.player

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class GradeGlEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        GradeShaderProgram(context, useHdr)
}

@UnstableApi
class GradeShaderProgram(
    context: Context,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private companion object {
        const val VERTEX = "shaders/gl/grade_vertex.glsl"
        const val FRAGMENT = "shaders/gl/grade_fragment.glsl"
        const val LUT_TEX_UNIT = 1
    }

    private val program: GlProgram = try {
        GlProgram(context, VERTEX, FRAGMENT)
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }

    private var lutTexId = 0
    private var lutSize = 0f
    private var loadedLutPath = ""

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val p = GradeBus.params
        try {
            ensureLut(GradeBus.lutPath)

            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)

            // ---- Layer 1 ----
            program.setFloatUniform("uTemp", p[GradeBus.I.TEMP])
            program.setFloatUniform("uTint", p[GradeBus.I.TINT])
            program.setFloatUniform("uEv", p[GradeBus.I.EV])
            program.setFloatUniform("uContrast", p[GradeBus.I.CONTRAST])
            program.setFloatUniform("uShadows", p[GradeBus.I.SHADOWS])
            program.setFloatUniform("uHighlights", p[GradeBus.I.HIGHLIGHTS])
            program.setFloatUniform("uRedRecovery", p[GradeBus.I.RED_RECOVERY])
            program.setFloatUniform("uAntiGreen", p[GradeBus.I.ANTI_GREEN])
            program.setFloatUniform("uMagentaGuard", p[GradeBus.I.MAGENTA_GUARD])
            program.setFloatUniform("uL1On", p[GradeBus.I.L1_ON])

            // ---- Layer 2 ----
            val lutOn = if (lutTexId != 0) p[GradeBus.I.L2_ON] else 0f
            program.setFloatUniform("uLutIntensity", p[GradeBus.I.LUT_INTENSITY])
            program.setFloatUniform("uLutSize", lutSize)
            program.setFloatUniform("uL2On", lutOn)

            // ---- Layer 3 ----
            val h = GradeBus.I.HSL_BASE
            program.setFloatsUniform("uHsl0", floatArrayOf(p[h], p[h + 1], p[h + 2]))
            program.setFloatsUniform("uHsl1", floatArrayOf(p[h + 3], p[h + 4], p[h + 5]))
            program.setFloatsUniform("uHsl2", floatArrayOf(p[h + 6], p[h + 7], p[h + 8]))
            program.setFloatsUniform("uHsl3", floatArrayOf(p[h + 9], p[h + 10], p[h + 11]))
            program.setFloatsUniform("uHsl4", floatArrayOf(p[h + 12], p[h + 13], p[h + 14]))
            program.setFloatsUniform("uHsl5", floatArrayOf(p[h + 15], p[h + 16], p[h + 17]))
            program.setFloatsUniform("uHsl6", floatArrayOf(p[h + 18], p[h + 19], p[h + 20]))
            program.setFloatUniform("uGlobalSat", p[GradeBus.I.GLOBAL_SAT])
            program.setFloatUniform("uSkinProtect", p[GradeBus.I.SKIN_PROTECT])
            program.setFloatUniform("uL3On", p[GradeBus.I.L3_ON])
            program.setFloatsUniform("uShadowTint", floatArrayOf(
                p[GradeBus.I.SHADOW_TINT_R], p[GradeBus.I.SHADOW_TINT_G], p[GradeBus.I.SHADOW_TINT_B]))
            program.setFloatsUniform("uSkinMask", floatArrayOf(
                p[GradeBus.I.SKIN_HUE], p[GradeBus.I.SKIN_TOL],
                p[GradeBus.I.SKIN_FEATHER], p[GradeBus.I.SKIN_STRENGTH]))
            program.setFloatsUniform("uSkinMask2", floatArrayOf(
                p[GradeBus.I.SKIN_ENABLE], p[GradeBus.I.SKIN_MASK_VIEW], 0.12f, 0.15f))

            program.bindAttributesAndUniforms()

            // LUT 3D phai gan thu cong: GlProgram khong biet sampler3D.
            // GlProgram.programId la private, nen lay id chuong trinh dang duoc
            // dung tu chinh GL (program.use() vua goi o tren) — khong phu thuoc
            // vao chi tiet noi bo cua Media3.
            if (lutTexId != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + LUT_TEX_UNIT)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
                val pid = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, pid, 0)
                if (pid[0] != 0) {
                    val loc = GLES20.glGetUniformLocation(pid[0], "uLutTex")
                    if (loc >= 0) GLES20.glUniform1i(loc, LUT_TEX_UNIT)
                }
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Nap lai texture 3D khi nguoi dung doi LUT */
    private fun ensureLut(path: String) {
        if (path == loadedLutPath) return
        loadedLutPath = path
        deleteLutTexture()
        if (path.isEmpty()) { lutSize = 0f; return }

        val lut = CubeLutParser.parse(path)
        if (lut == null) { lutSize = 0f; return }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id)
        // NEAREST: shader tu noi suy tu dien, khong de phan cung trilinear can thiep
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        val bb = ByteBuffer.allocateDirect(lut.rgb.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(lut.rgb)
        bb.rewind()
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, /* level= */ 0, GLES30.GL_RGB16F,
            lut.size, lut.size, lut.size, /* border= */ 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, bb
        )
        lutTexId = id
        lutSize = lut.size.toFloat()
    }

    private fun deleteLutTexture() {
        if (lutTexId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTexId), 0)
            lutTexId = 0
        }
    }

    override fun release() {
        super.release()
        deleteLutTexture()
        try { program.delete() } catch (_: GlUtil.GlException) { }
    }
}
