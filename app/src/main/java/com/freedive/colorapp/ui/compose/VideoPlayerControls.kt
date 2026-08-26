// ============================================================================
//  VIDEO PLAYER CONTROLS — lop dieu khien phat lai (Jetpack Compose)
//  Thanh phan:
//    · GradePlayerSurface   — SurfaceView gan vao ExoPlayer qua AndroidView
//    · VideoPlayerControls  — lop phu: phat/dung, thanh tua, buoc tung khung
//                             hinh, dong ho timecode
//    · GradePlayerScreen    — gop ca hai, dung truc tiep trong ComposeView
//
//  Vi sao co nut buoc tung khung hinh: kiem tra BANDING o vung chuyen mau nuoc
//  phai soi tren dung mot khung, khong the vua phat vua danh gia.
//  ExoPlayer da dat SeekParameters.EXACT nen moi buoc nhay dung mot frame.
// ============================================================================
package com.freedive.colorapp.ui.compose

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.freedive.colorapp.player.GradePlayer
import com.freedive.colorapp.ui.L
import com.freedive.colorapp.ui.Theme
import kotlinx.coroutines.delay

private val Bg = Color(Theme.BG)
private val Surface = Color(Theme.SURFACE)
private val Accent = Color(Theme.ACCENT)
private val TextMain = Color(Theme.TEXT)
private val TextDim = Color(Theme.TEXT_DIM)

// ---------------------------------------------------------------------------
/** Mat phat: SurfaceView thuan, ExoPlayer ve thang vao day sau khi qua shader */
@UnstableApi
@Composable
fun GradePlayerSurface(gradePlayer: GradePlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { gradePlayer.player.setVideoSurfaceView(it) }
        },
        onRelease = { gradePlayer.player.clearVideoSurface() }
    )
}

// ---------------------------------------------------------------------------
/**
 * Lop dieu khien. Vi tri phat duoc hoi lai moi 100ms — ExoPlayer khong phat
 * su kien position lien tuc, va 100ms du muot cho thanh tua ma khong ton pin.
 */
@UnstableApi
@Composable
fun VideoPlayerControls(
    gradePlayer: GradePlayer,
    modifier: Modifier = Modifier,
) {
    val player = gradePlayer.player

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.let { if (it > 0) it else 0L }
            }
            delay(100)
        }
    }

    val progress = when {
        scrubbing -> scrubValue
        durationMs > 0 -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val shownMs = if (scrubbing && durationMs > 0) (scrubValue * durationMs).toLong() else positionMs

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ---- Thanh tua ----
        Slider(
            value = progress,
            onValueChange = { v ->
                scrubbing = true
                scrubValue = v
            },
            onValueChangeFinished = {
                if (durationMs > 0) gradePlayer.seekTo((scrubValue * durationMs).toLong())
                scrubbing = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color(Theme.STROKE_ON).copy(alpha = 0.25f),
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // ---- Hang nut + timecode ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            GlyphButton(
                glyph = if (isPlaying) "⏸" else "▶",
                description = if (isPlaying) L.t("Tạm dừng", "Pause") else L.t("Phát", "Play"),
                highlighted = isPlaying,
            ) { gradePlayer.togglePlay() }

            GlyphButton("⏮", L.t("Lùi 1 giây", "Back 1 second")) { gradePlayer.jump(-1000L) }
            GlyphButton("◀|", L.t("Lùi 1 khung hình", "Previous frame")) { gradePlayer.stepFrame(-1) }
            GlyphButton("|▶", L.t("Tới 1 khung hình", "Next frame")) { gradePlayer.stepFrame(+1) }
            GlyphButton("⏭", L.t("Tới 1 giây", "Forward 1 second")) { gradePlayer.jump(+1000L) }

            Text(
                text = "${clock(shownMs)} / ${clock(durationMs)}",
                color = TextMain,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
/**
 * Nut ky tu. Dung Text thay Icon vector de khong keo them thu vien
 * material-icons-extended chi vi 5 bieu tuong.
 */
@Composable
private fun GlyphButton(
    glyph: String,
    description: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Text(
            text = glyph,
            color = if (highlighted) Accent else TextDim,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** mm:ss.d — mot chu so phan muoi giay, du de doi soat frame ma van gon */
private fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val m = t / 60000L
    val s = (t % 60000L) / 1000L
    val d = (t % 1000L) / 100L
    return "%02d:%02d.%d".format(m, s, d)
}

// ---------------------------------------------------------------------------
/** Man hinh phat hoan chinh — mat phat + lop dieu khien duoi */
@UnstableApi
@Composable
fun GradePlayerScreen(gradePlayer: GradePlayer, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Bg)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            GradePlayerSurface(gradePlayer, Modifier.fillMaxSize())
            Text(
                text = L.t("Nhấn giữ để so sánh ảnh gốc", "Hold to compare with original"),
                color = Color(Theme.TEXT_MUTED),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        }
        VideoPlayerControls(gradePlayer)
    }
}
