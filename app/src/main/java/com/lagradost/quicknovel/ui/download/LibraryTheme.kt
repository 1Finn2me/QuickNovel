package com.lagradost.quicknovel.ui.download

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.lagradost.quicknovel.R

/**
 * Helper to get colors from Android theme attributes
 */
fun Context.getColorFromAttr(@AttrRes attrRes: Int): Color {
    val typedValue = TypedValue()
    theme.resolveAttribute(attrRes, typedValue, true)
    return Color(typedValue.data)
}

/**
 * Theme colors extracted from Android XML theme
 */
data class LibraryThemeColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainer: Color,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val outline: Color,
) {
    companion object {
        // Status colors remain constant across themes
        val newChapters = Color(0xFF10B981)
        val reading = Color(0xFF3B82F6)
        val completed = Color(0xFF22C55E)
        val onHold = Color(0xFFF59E0B)
        val planToRead = Color(0xFF8B5CF6)
        val dropped = Color(0xFFEF4444)
        val downloaded = Color(0xFF06B6D4)
        val imported = Color(0xFF9333EA)

        // Download states
        val downloading = Color(0xFF3B82F6)
        val paused = Color(0xFFF59E0B)
        val failed = Color(0xFFEF4444)
        val pending = Color(0xFF6B7280)
    }
}

@Composable
fun rememberLibraryThemeColors(): LibraryThemeColors {
    val context = LocalContext.current

    return remember(context.theme) {
        LibraryThemeColors(
            background = context.getColorFromAttr(R.attr.primaryBlackBackground),
            surface = context.getColorFromAttr(R.attr.primaryGrayBackground),
            surfaceVariant = context.getColorFromAttr(R.attr.boxItemBackground),
            surfaceContainer = context.getColorFromAttr(R.attr.iconGrayBackground),
            primary = context.getColorFromAttr(R.attr.colorPrimary),
            primaryContainer = context.getColorFromAttr(R.attr.colorPrimary).copy(alpha = 0.2f),
            onPrimary = context.getColorFromAttr(R.attr.colorOnPrimary),
            textPrimary = context.getColorFromAttr(R.attr.textColor),
            textSecondary = context.getColorFromAttr(R.attr.grayTextColor),
            outline = context.getColorFromAttr(R.attr.grayTextColor).copy(alpha = 0.3f),
        )
    }
}