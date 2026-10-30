package com.xuanthanhtnt.xtplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuanthanhtnt.xtplayer.core.CrashLog
import com.xuanthanhtnt.xtplayer.ui.theme.XtColors

/** After a crash: shows what went wrong, to copy or share with the developer. */
@Composable
fun CrashReportPrompt() {
    var report by remember { mutableStateOf(CrashLog.last()) }
    val text = report ?: return
    CrashReportDialog(text, title = "XT-Player bị dừng lần trước") {
        CrashLog.clear()
        report = null
    }
}

@Composable
fun CrashReportDialog(text: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Gửi nội dung dưới đây cho nhà phát triển để sửa lỗi (bấm Sao chép hoặc Chia sẻ).",
                    style = MaterialTheme.typography.bodySmall,
                    color = XtColors.secondaryText,
                )
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { share(context, text) }) { Text("Chia sẻ") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { copy(context, text) }) { Text("Sao chép") }
                TextButton(onClick = onDismiss) { Text("Đóng") }
            }
        },
    )
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("XT-Player crash", text))
}

private fun share(context: Context, text: String) {
    runCatching {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Gửi nhật ký lỗi")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
