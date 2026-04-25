package live.agor.app.ui.messageblocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import live.agor.app.models.InputRequestContent
import live.agor.app.models.InputRequestKind
import live.agor.app.models.InputRequestStatus

@Composable
fun InputRequestCardView(
    request: InputRequestContent,
    onAnswer: (List<String>) -> Unit,
) {
    val pending = request.status == InputRequestStatus.PENDING
    val container = if (pending) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth().background(container, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.QuestionAnswer, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Input requested", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        if (request.questions.isEmpty()) {
            Text("(no question provided)", style = MaterialTheme.typography.bodyMedium)
            return
        }
        // First question only — typical case in agentic flows
        val q = request.questions.first()
        Text(q.question, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        when (q.kind) {
            InputRequestKind.FREE_TEXT -> {
                var text by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Your answer") },
                    enabled = pending,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onAnswer(listOf(text)) }, enabled = pending && text.isNotBlank()) {
                        Text("Send")
                    }
                }
            }
            InputRequestKind.SINGLE_CHOICE -> {
                var selected by remember { mutableStateOf(-1) }
                q.options?.forEachIndexed { idx, opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = pending) { selected = idx }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == idx, onClick = { selected = idx }, enabled = pending)
                        Spacer(Modifier.width(6.dp))
                        Text(opt)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onAnswer(listOf(q.options?.getOrNull(selected).orEmpty())) },
                        enabled = pending && selected >= 0,
                    ) { Text("Send") }
                }
            }
            InputRequestKind.MULTI_CHOICE -> {
                val selected = remember { mutableStateListOf<Int>() }
                q.options?.forEachIndexed { idx, opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = pending) {
                                if (idx in selected) selected.remove(idx) else selected.add(idx)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = idx in selected, onCheckedChange = {
                            if (idx in selected) selected.remove(idx) else selected.add(idx)
                        }, enabled = pending)
                        Spacer(Modifier.width(6.dp))
                        Text(opt)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onAnswer(selected.mapNotNull { q.options?.getOrNull(it) }) },
                        enabled = pending && selected.isNotEmpty(),
                    ) { Text("Send") }
                }
            }
        }
    }
}
