// ============================================================================
//  GRADE PLAYER — ExoPlayer (Media3) cau hinh cho viec soi mau
//  Diem khac mot player thong thuong:
//   · setSeekParameters(EXACT) — tua den DUNG frame, khong nhay ve I-frame gan
//     nhat. Bat buoc de soi banding tren mot khung hinh cu the.
//   · REPEAT_MODE_ONE — clip lap vo han, khong phai bam phat lai.
//   · setVideoEffects(...) — cam engine mau vao pipeline video: LUT va moi
//     slider deu ap theo THOI GIAN THUC ngay tren khung dang hien.
//   · Video 10-bit: khong yeu cau tone-mapping. File D-Log M cua DJI gan the
//     bt709 nen Media3 truyen thang gia tri log xuong shader — dung cai ma
//     ham dlogMToLinear() trong grade_fragment.glsl mong doi.
// ============================================================================
package com.freedive.colorapp.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

@UnstableApi
class GradePlayer(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ONE
            setVideoEffects(listOf(GradeGlEffect(), ClarityGlEffect()))
            playWhenReady = false
        }

    /** Do dai mot khung hinh (ms) — suy tu fps that cua file, mac dinh 30 fps */
    val frameDurationMs: Long
        get() {
            val fps = player.videoFormat?.frameRate ?: 30f
            val safe = if (fps > 1f) fps else 30f
            return (1000f / safe).toLong().coerceAtLeast(1L)
        }

    fun open(uri: Uri, autoPlay: Boolean = true) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = autoPlay
    }

    fun togglePlay() { player.playWhenReady = !player.playWhenReady }

    fun seekTo(ms: Long) {
        val dur = player.duration
        val max = if (dur > 0) dur else Long.MAX_VALUE
        player.seekTo(ms.coerceIn(0L, max))
    }

    /** Buoc tung khung hinh — tu dong dung phat truoc khi nhay */
    fun stepFrame(delta: Int) {
        player.playWhenReady = false
        seekTo(player.currentPosition + delta * frameDurationMs)
    }

    fun jump(ms: Long) {
        player.playWhenReady = false
        seekTo(player.currentPosition + ms)
    }

    fun release() {
        player.release()
    }
}
