package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * `text-field`: Material outlined field with a transparent container (it sits on a glass card), `rounded.sm`, `outline`
 * border and [label] in `text-secondary`, inside the field while it is empty and unfocused and on the top border once it
 * is focused or filled (Material's own animation, `body` to `caption`). Focused: `accent` border, `accent-text` label.
 * On error the border, the label and [errorText] (supporting text) use `error`; the text is announced politely and is the
 * field's TalkBack error. Typing uses the system keyboard.
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
        modifier = modifier.fillMaxWidth().semantics { if (errorText != null) error(errorText) },
        textStyle = PpsTheme.typography.body,
        // No forced style: Material animates the label between bodyLarge (`body`) and bodySmall (`caption`) as it floats.
        label = { Text(text = label) },
        supportingText =
            errorText?.let {
                {
                    Text(
                        text = it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = PpsTheme.typography.caption,
                    )
                }
            },
        isError = errorText != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        shape = PpsTheme.shapes.sm,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                errorTextColor = colors.text,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                cursorColor = colors.text,
                errorCursorColor = colors.error,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.outline,
                errorBorderColor = colors.error,
                focusedLabelColor = colors.accentText,
                unfocusedLabelColor = colors.textSecondary,
                errorLabelColor = colors.error,
                errorSupportingTextColor = colors.error,
            ),
    )
}
