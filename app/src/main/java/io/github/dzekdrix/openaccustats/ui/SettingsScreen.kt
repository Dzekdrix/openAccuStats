package io.github.dzekdrix.openaccustats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dzekdrix.openaccustats.BuildConfig
import io.github.dzekdrix.openaccustats.R

@Composable
fun SettingsScreen(vm: MainViewModel, padding: PaddingValues) {
    val manual by vm.manualDesignMah.collectAsStateWithLifecycle()
    Column(
        Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DesignCard(vm.detectedDesignMah, manual, onSave = vm::setManualDesign)
        DiagnosticCard()
        AboutCard()
    }
}

@Composable
private fun DesignCard(detected: Double?, manual: Double?, onSave: (Double?) -> Unit) = Card(Modifier.fillMaxWidth()) {
    val focus = LocalFocusManager.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.design_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.design_help), style = MaterialTheme.typography.bodySmall)

        Metric(
            stringResource(R.string.design_detected_label),
            detected?.let { stringResource(R.string.unit_mah, it) } ?: stringResource(R.string.design_not_detected)
        )
        Metric(
            stringResource(R.string.design_configured_label),
            manual?.let { stringResource(R.string.unit_mah, it) } ?: stringResource(R.string.design_configured_none)
        )
        // Valeur effectivement utilisée pour calculer la santé
        Text(
            when {
                manual != null -> stringResource(R.string.design_used_manual)
                detected != null -> stringResource(R.string.design_used_detected)
                else -> stringResource(R.string.design_used_none)
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )

        var text by remember(manual) { mutableStateOf(manual?.let { "%.0f".format(it) } ?: "") }
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
            label = { Text(stringResource(R.string.design_manual_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(text.toDoubleOrNull()?.takeIf { it > 0 })
                focus.clearFocus()
            }) { Text(stringResource(R.string.btn_save)) }
            TextButton(onClick = {
                text = ""
                onSave(null)
                focus.clearFocus()
            }) { Text(stringResource(R.string.btn_clear)) }
        }
    }
}

@Composable
private fun AboutCard() = Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.about_licence), style = MaterialTheme.typography.bodySmall)
    }
}
