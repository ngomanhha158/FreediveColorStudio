// ============================================================================
//  CLARITY EFFECT — pass thu hai trong chuoi hieu ung cua Media3
//  Tach rieng khoi GradeGlEffect vi unsharp mask can lay mau 8 diem lan can
//  cua ANH DA GRADE; gop chung se phai chay lai ca chuoi mau 9 lan moi pixel.
//  isNoOp(): khi Clarity ~ 0, Media3 bo qua han pass nay — khong ton GPU.
// ============================================================================
package com.freedive.colorapp.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.abs

@UnstableApi
class ClarityGlEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClarityShaderProgram(context, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        abs(GradeBus.params[GradeBus.I.CLARITY]) < 0.001f
}

@UnstableApi
class ClarityShaderProgram(
    context: Context,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private companion object {
        const val VERTEX = "shaders/gl/grade_vertex.glsl"
        const val FRAGMENT = "shaders/gl/clarity_fragment.glsl"
    }

    private val program: GlProgram = try {
        GlProgram(context, VERTEX, FRAGMENT)
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }

    private var width = 1
    private var height = 1

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = if (inputWidth > 0) inputWidth else 1
        height = if (inputHeight > 0) inputHeight else 1
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatUniform("uClarity", GradeBus.params[GradeBus.I.CLARITY])
            program.setFloatUniform("uTexelW", 1f / width)
            program.setFloatUniform("uTexelH", 1f / height)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (_: GlUtil.GlException) { }
    }
}
