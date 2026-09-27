package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * `text-field`: Material outlined field on `surface-variant`, `rounded.sm`, `outline` border; on error the border
 * and [errorText] (supporting text) use `error`. Labels use the system keyboard.
 */
@Composable
fun PpsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
) {
    val colors = PpsTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = PpsTheme.typography.body,
        label = { Text(text = label, style = PpsTheme.typography.label) },
        supportingText = errorText?.let { { Text(text = it, style = PpsTheme.typography.caption) } },
        isError = errorText != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        shape = PpsTheme.shapes.sm,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                errorTextColor = colors.text,
                focusedContainerColor = colors.surfaceVariant,
                unfocusedContainerColor = colors.surfaceVariant,
                errorContainerColor = colors.surfaceVariant,
                cursorColor = colors.text,
                errorCursorColor = colors.error,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.outline,
                errorBorderColor = colors.error,
                focusedLabelColor = colors.textSecondary,
                unfocusedLabelColor = colors.textSecondary,
                errorLabelColor = colors.error,
                errorSupportingTextColor = colors.error,
            ),
    )
}
