package com.ultratv.tv.nativeapp.ui.parental

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.parental.ParentalStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import javax.inject.Inject

@HiltViewModel
class ParentalViewModel @Inject constructor(
    private val store: ParentalStore,
) : ViewModel() {

    val pinSet: StateFlow<Boolean> = store.pinHash
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setPin(pin: String, onDone: () -> Unit) {
        viewModelScope.launch { store.setPin(pin); onDone() }
    }

    suspend fun check(pin: String): Boolean = store.check(pin)
}

@Composable
fun ParentalSection(
    vm: ParentalViewModel = hiltViewModel(),
    onManageLockedChannels: () -> Unit = {},
) {
    val set by vm.pinSet.collectAsState()
    var dialog by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (set) "Parental PIN: enabled (4-digit)" else "Parental PIN: not set",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Button(onClick = { dialog = true }) { Text(if (set) "Change PIN" else "Set PIN") }
        if (set) Button(onClick = { vm.setPin("") {} }) { Text("Clear") }
        Button(onClick = onManageLockedChannels) { Text("Manage locked channels…") }
    }
    if (dialog) {
        PinSetDialog(onCancel = { dialog = false }, onConfirm = { pin ->
            vm.setPin(pin) { dialog = false }
        })
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun PinSetDialog(onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Set parental PIN", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            PinField(value = p1, onChange = { p1 = it.filter { c -> c.isDigit() }.take(4) }, hint = "PIN (4 digits)")
            PinField(value = p2, onChange = { p2 = it.filter { c -> c.isDigit() }.take(4) }, hint = "Confirm PIN")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = { if (p1.length == 4 && p1 == p2) onConfirm(p1) },
                    enabled = p1.length == 4 && p1 == p2,
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, hint: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                inner()
            },
        )
    }
}
