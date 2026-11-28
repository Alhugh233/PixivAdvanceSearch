package com.pixiv.pixiv.advancesearch

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

internal data class M3eDialogData(
    val title: String? = null,
    val message: String? = null,
    val items: List<String>? = null,
    val singleChoice: Boolean = false,
    val checkedItem: Int = -1,
    val positive: String? = null,
    val negative: String? = null,
    val neutral: String? = null,
    val cancelable: Boolean = true,
    val onItem: ((Int) -> Unit)? = null,
    val onPositive: (() -> Unit)? = null,
    val onNegative: (() -> Unit)? = null,
    val onNeutral: (() -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null
)

internal fun showM3eDialog(
    activity: Activity,
    hostContext: Context,
    moduleResources: Resources,
    data: M3eDialogData
): Dialog {
    val moduleContext = SettingsComposeContext(hostContext, moduleResources)
    val owner = SettingsComposeOwner(activity)
    val sheet = !data.items.isNullOrEmpty()
    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    dialog.setCancelable(data.cancelable)
    dialog.setCanceledOnTouchOutside(data.cancelable)
    dialog.setOnCancelListener { data.onCancel?.invoke() }
    dialog.setOnDismissListener {
        owner.dispose()
        data.onDismiss?.invoke()
    }

    val composeView = ComposeView(moduleContext).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent {
            CompositionLocalProvider(
                LocalContext provides moduleContext,
                LocalResources provides moduleResources
            ) {
                ModuleSettingsTheme {
                    if (sheet) {
                        M3eSheet(data) { dialog.dismiss() }
                    } else {
                        M3eAlert(data) { dialog.dismiss() }
                    }
                }
            }
        }
    }
    dialog.setContentView(composeView)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(0.32f)
        if (sheet) {
            setGravity(Gravity.BOTTOM)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        } else {
            setGravity(Gravity.CENTER)
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }
    return dialog
}

@Composable
private fun M3eAlert(data: M3eDialogData, onDismiss: () -> Unit) {
    val positive = data.positive
    val negative = data.negative
    val neutral = data.neutral
    Surface(
        modifier = Modifier
            .width(340.dp)
            .wrapContentHeight(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(24.dp)) {
            data.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
            }
            data.message?.takeIf { it.isNotBlank() }?.let {
                SelectionContainer {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (neutral != null) {
                    TextButton(onClick = {
                        data.onNeutral?.invoke()
                        onDismiss()
                    }) { Text(neutral) }
                }
                if (negative != null) {
                    TextButton(onClick = {
                        data.onNegative?.invoke()
                        onDismiss()
                    }) { Text(negative) }
                }
                if (positive != null) {
                    Button(onClick = {
                        data.onPositive?.invoke()
                        onDismiss()
                    }) { Text(positive) }
                } else if (neutral == null && negative == null) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun M3eSheet(data: M3eDialogData, onDismiss: () -> Unit) {
    var selected by remember { mutableIntStateOf(data.checkedItem) }
    val positive = data.positive
    val negative = data.negative
    val neutral = data.neutral
    val hasButtons = positive != null || negative != null || neutral != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
            Spacer(Modifier.height(12.dp))
            data.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            data.message?.takeIf { it.isNotBlank() }?.let {
                SelectionContainer {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .padding(horizontal = 24.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                data.items?.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = index
                                data.onItem?.invoke(index)
                                if (!data.singleChoice) onDismiss()
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (data.singleChoice) {
                            RadioButton(
                                selected = index == selected,
                                onClick = null
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            if (hasButtons) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (neutral != null) {
                        TextButton(onClick = {
                            data.onNeutral?.invoke()
                            onDismiss()
                        }) { Text(neutral) }
                    }
                    if (negative != null) {
                        TextButton(onClick = {
                            data.onNegative?.invoke()
                            onDismiss()
                        }) { Text(negative) }
                    }
                    if (positive != null) {
                        Button(onClick = {
                            data.onPositive?.invoke()
                            onDismiss()
                        }) { Text(positive) }
                    }
                }
            } else if (data.cancelable) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
