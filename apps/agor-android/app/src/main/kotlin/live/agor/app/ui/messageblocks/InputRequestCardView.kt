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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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

        val answers = remember(request.inputRequestId) { mutableStateMapOf<Int, String>() }
        request.questions.forEachIndexed { questionIndex, q ->
            if (questionIndex > 0) Spacer(Modifier.height(12.dp))
            q.header?.takeIf { it.isNotBlank() }?.let { header ->
                Text(
                    header,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(q.question, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))

            val options = q.options.orEmpty()
            val kind = when {
                options.isNotEmpty() && q.multiSelect == true -> InputRequestKind.MULTI_CHOICE
                options.isNotEmpty() -> InputRequestKind.SINGLE_CHOICE
                else -> q.kind
            }

            if (!pending && request.answers?.containsKey(q.question) == true) {
                Text(
                    request.answers[q.question].orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@forEachIndexed
            }

            when (kind) {
                InputRequestKind.FREE_TEXT -> {
                    OutlinedTextField(
                        value = answers[questionIndex].orEmpty(),
                        onValueChange = { answers[questionIndex] = it },
                        placeholder = { Text("Your answer") },
                        enabled = pending,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                InputRequestKind.SINGLE_CHOICE -> {
                    options.forEach { opt ->
                        val selected = answers[questionIndex] == opt.label
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = pending) { answers[questionIndex] = opt.label }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { answers[questionIndex] = opt.label },
                                enabled = pending,
                            )
                            Spacer(Modifier.width(6.dp))
                            OptionText(label = opt.label, description = opt.description, markdown = opt.markdown)
                        }
                    }
                }
                InputRequestKind.MULTI_CHOICE -> {
                    val selectedLabels = answers[questionIndex]
                        ?.split(", ")
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        .orEmpty()
                    options.forEach { opt ->
                        val selected = opt.label in selectedLabels
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = pending) {
                                    answers[questionIndex] = toggledLabels(selectedLabels, opt.label)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = {
                                    answers[questionIndex] = toggledLabels(selectedLabels, opt.label)
                                },
                                enabled = pending,
                            )
                            Spacer(Modifier.width(6.dp))
                            OptionText(label = opt.label, description = opt.description, markdown = opt.markdown)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            val allAnswered = request.questions.indices.all { index ->
                answers[index]?.isNotBlank() == true
            }
            Button(
                onClick = {
                    onAnswer(request.questions.indices.map { answers[it].orEmpty() })
                },
                enabled = pending && allAnswered,
            ) {
                Text(if (request.questions.size > 1) "Send answers" else "Send")
            }
        }
    }
}

@Composable
private fun OptionText(label: String, description: String, markdown: String?) {
    Column {
        Text(label)
        if (description.isNotBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        markdown?.takeIf { it.isNotBlank() }?.let {
            MarkdownText(markdown = it)
        }
    }
}

private fun toggledLabels(current: Set<String>, label: String): String =
    if (label in current) {
        current.filterNot { it == label }.joinToString(", ")
    } else {
        (current + label).joinToString(", ")
    }
