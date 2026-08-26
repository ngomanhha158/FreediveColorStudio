// ============================================================================
//  TASK E4 — BAN THAM CHIEU JETPACK COMPOSE (theo nguyen van spec 4.2.3 + 4.4.3)
//  App hien tai dung classic View (QD12/QD14) — file nay dat NGOAI sourceSet
//  (docs/compose-ref/), san sang dung khi migrate Compose.
//  Can dependency Compose nhu ghi chu trong SmartGuideHighlight.kt.
// ============================================================================
package com.freedive.colorapp.lut

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/**
 * Spec 4.2.3: "LazyRow cuon ngang doc thu muc noi bo, hien LUT nhu nut chon".
 * Spec 4.4.3: nhan giu -> DropdownMenu voi Rename / Delete.
 * thumbnails: map path -> Bitmap DA AP LUT (tao boi NativeBridge.applyLutToBitmap).
 */
@Composable
fun LutGalleryRow(
    entries: List<LutEntry>,
    thumbnails: Map<String, Bitmap>,
    selectedPath: String?,
    onSelect: (LutEntry) -> Unit,
    onRename: (LutEntry) -> Unit,
    onDelete: (LutEntry) -> Unit,
) {
    LazyRow {
        items(entries, key = { it.file.absolutePath }) { entry ->
            var menuOpen by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .padding(end = 8.dp)
                    .combinedClickable(
                        onClick = { onSelect(entry) },
                        onLongClick = { menuOpen = true },   // spec 4.4.3
                    )
            ) {
                thumbnails[entry.file.absolutePath]?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = entry.name,
                        modifier = Modifier.size(width = 96.dp, height = 54.dp),
                    )
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.file.absolutePath == selectedPath)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Doi ten") },
                        onClick = { menuOpen = false; onRename(entry) })
                    DropdownMenuItem(text = { Text("Xoa") },
                        onClick = { menuOpen = false; onDelete(entry) })
                }
            }
        }
    }
}

/* Vi du lap ghep voi category (spec 4.4.4):
 *   repo.list().forEach { (category, entries) ->
 *       Text(category, style = MaterialTheme.typography.labelMedium)
 *       LutGalleryRow(entries, thumbs, selected, ::select, ::rename, ::delete)
 *   }
 */
