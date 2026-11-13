package app.shilex.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shilex.android.local.ActivityLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the assistant did recently, newest first; kept in memory only. */
@Composable
fun ActivityScreen(entries: List<ActivityLog.Entry>, onClear: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Activity",
                style = MaterialTheme.typography.headlineSmall,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("Clear", color = CoralError, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            "What was done, not what was said. Cleared when the app closes.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(bottom = 14.dp),
        )

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlassCard(horizontalPadding = 24, verticalPadding = 20) {
                    Text(
                        "Nothing yet. What you ask shows up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = floatingDockContentBottomPadding()),
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { idx, entry ->
                        ActivityRow(entry)
                        if (idx < entries.lastIndex) {
                            GlassDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityRow(entry: ActivityLog.Entry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.what,
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.result,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Pill(
                if (entry.local) "On phone" else "Gemini",
                if (entry.local) ElectricCyan else AmberWarning,
                dot = false,
            )
            Text(
                SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(entry.at)),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
