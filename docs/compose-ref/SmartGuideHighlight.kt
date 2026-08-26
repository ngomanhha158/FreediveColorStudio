// ============================================================================
//  TASK S1 — BAN THAM CHIEU JETPACK COMPOSE (theo nguyen van spec)
//
//  LUU Y KIEN TRUC: app hien tai dung classic View + MDC theme (QD12 — khong
//  viet lai UI bang Compose o giai doan RC). File nay la BAN THAM CHIEU day du
//  cho yeu cau "Compose UI implementation for the pulsating/highlight effect"
//  cua spec, san sang dung ngay khi du an migrate sang Compose.
//
//  DE BIEN DICH FILE NAY can them vao app/build.gradle.kts:
//      buildFeatures { compose = true }
//      composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
//      implementation(platform("androidx.compose:compose-bom:2025.05.00"))
//      implementation("androidx.compose.material3:material3")
//  File nay dat tai docs/compose-ref/ (NGOAI sourceSet) de khong pha build
//  hien tai; khi migrate Compose, copy vao src/main/java/.../guide/.
// ============================================================================
package com.freedive.colorapp.guide

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Mau accent "Ocean" dong bo voi themes.xml */
private val GuideAccent = Color(0xFF3987E5)

/**
 * Wrapper cho MOT slider/section: khi [active] = true, vien phat sang dap
 * theo nhip (pulse 700ms, dao nguoc) + do bong lan toa — dung spec
 * "subtle glowing border or a pulsating animation".
 */
@Composable
fun GuideHighlight(
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!active) {                       // Pro Guide tat / khong phai buoc ke tiep
        androidx.compose.foundation.layout.Box(modifier) { content() }
        return
    }
    val pulse = rememberInfiniteTransition(label = "guide-pulse")
    val a by pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "guide-alpha",
    )
    androidx.compose.foundation.layout.Box(
        modifier
            .shadow(elevation = (6 * a).dp, shape = RoundedCornerShape(10.dp),
                    ambientColor = GuideAccent, spotColor = GuideAccent)
            .border(width = 1.5.dp, color = GuideAccent.copy(alpha = a),
                    shape = RoundedCornerShape(10.dp))
            .padding(6.dp)
    ) { content() }
}

/**
 * Hang dieu khien "Pro Guide": Switch bat/tat (spec muc 3) + dong goi y
 * cua buoc hien tai. Tat -> [SmartGuideManager] phat GuideStep.OFF va moi
 * [GuideHighlight] tro ve trang thai thuong.
 */
@Composable
fun ProGuideBar(manager: SmartGuideManager, modifier: Modifier = Modifier) {
    val st by manager.state.collectAsState()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = st.step != GuideStep.OFF,
            onCheckedChange = { manager.setProMode(it) },
        )
        Text(
            text = if (st.step == GuideStep.OFF) "Pro Guide" else st.hint,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/* ----------------------------------------------------------------------------
 * Vi du lap ghep (khong compile trong app View hien tai — chi minh hoa):
 *
 *   val guide = remember { SmartGuideManager() }
 *   val st by guide.state.collectAsState()
 *   ProGuideBar(guide)
 *   GuideHighlight(active = GuideKeys.TINT in st.targets) {
 *       TintSlider(value, onChange = { v ->
 *           grade.tint = v; guide.onGradeChanged(grade)
 *       })
 *   }
 * ------------------------------------------------------------------------- */
