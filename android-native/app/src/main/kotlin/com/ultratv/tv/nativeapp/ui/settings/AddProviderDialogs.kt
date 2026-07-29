package com.ultratv.tv.nativeapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Full-screen modal scrim hosting a form. Lets us keep the Settings list above
 * 100% text/buttons/switches so D-pad scroll never lands on a TextField that
 * would summon the IME mid-scroll. Inputs only ever appear when the user has
 * explicitly opened one of these dialogs.
 */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun AddProviderDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    canSubmit: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 720.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            content()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = onSubmit,
                    enabled = canSubmit,
                ) { Text(S.addProviderAdd, fontSize = 15.sp) }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                ) { Text(S.cancel, fontSize = 15.sp) }
            }
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    autoFocus: Boolean = false,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background)
                .androidx_border(focused)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                ),
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    }
                    inner()
                },
            )
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamDialog(onDismiss: () -> Unit, onSubmit: (name: String, url: String, user: String, pass: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val url = "http://soow.top:80"
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val canSubmit = user.isNotBlank() && pass.isNotBlank()
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    AddProviderDialog(
        title = S.addProviderXtreamTitle,
        onDismiss = onDismiss,
        onSubmit = { onSubmit(name, url, user, pass) },
        canSubmit = canSubmit,
    ) {
        FormField(S.fieldNameOptional, name, { name = it })
        FormField(S.fieldUsername, user, { user = it })
        FormField(S.fieldPassword, pass, { pass = it }, password = true)
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun M3uDialog(onDismiss: () -> Unit, onSubmit: (name: String, url: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    AddProviderDialog(
        title = S.addProviderM3uTitle,
        onDismiss = onDismiss,
        onSubmit = { onSubmit(name, url) },
        canSubmit = url.isNotBlank(),
    ) {
        FormField(S.fieldNameOptional, name, { name = it })
        FormField(S.fieldPlaylistUrl, url, { url = it }, keyboardType = KeyboardType.Uri,
            placeholder = "https://host.tld/playlist.m3u")
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun StalkerDialog(onDismiss: () -> Unit, onSubmit: (name: String, url: String, mac: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    val S = com.ultratv.tv.nativeapp.i18n.LocalStrings.current
    AddProviderDialog(
        title = S.addProviderStalkerTitle,
        onDismiss = onDismiss,
        onSubmit = { onSubmit(name, url, mac) },
        canSubmit = url.isNotBlank() && mac.length in 12..17,
    ) {
        FormField(S.fieldNameOptional, name, { name = it })
        FormField(S.fieldPortalUrl, url, { url = it }, keyboardType = KeyboardType.Uri,
            placeholder = "http://host:8080")
        FormField(S.fieldDeviceMac, mac, { mac = it.uppercase() },
            placeholder = "00:1A:79:XX:XX:XX")
    }
}

@Composable
private fun Modifier.androidx_border(focused: Boolean): Modifier = this.border(
    width = 1.dp,
    color = if (focused) com.ultratv.tv.nativeapp.ui.theme.UltraTokens.Accent else com.ultratv.tv.nativeapp.ui.theme.UltraTokens.Line2,
    shape = RoundedCornerShape(8.dp),
)
