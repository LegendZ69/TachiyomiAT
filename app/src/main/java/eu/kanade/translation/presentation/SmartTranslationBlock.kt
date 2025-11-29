package eu.kanade.translation.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.max

@Composable
fun SmartTranslationBlock(
    modifier: Modifier = Modifier,
    block: TranslationBlock,
    scaleFactor: Float,
    fontFamily: FontFamily,
) {
    if (block.translation.isBlank()) return

    // Add padding based on symbol dimensions to prevent clipping
    val padX = block.symWidth * 0.5f 
    val padY = block.symHeight * 0.5f
    
    val xPx = max((block.x - padX / 2) * scaleFactor, 0.0f)
    val yPx = max((block.y - padY / 2) * scaleFactor, 0.0f)
    
    val width = ((block.width + padX) * scaleFactor).pxToDp()
    val height = ((block.height + padY) * scaleFactor).pxToDp()
    
    Box(
        modifier = modifier
            .wrapContentSize(Alignment.TopStart, true)
            .offset(xPx.pxToDp(), yPx.pxToDp())
            .requiredSize(width, height)
    ) {
        AutoSizableText(
            text = block.translation,
            fontFamily = fontFamily,
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.matchParentSize()
        )
    }
}

@Composable
fun AutoSizableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    minFontSize: TextUnit = 6.sp,
    maxFontSize: TextUnit = 100.sp,
) {
    val density = LocalDensity.current
    val fontSize = remember { mutableStateOf(maxFontSize) }
    
    // Add text shadow/outline for better readability
    val style = TextStyle(
        shadow = Shadow(
            color = Color.White,
            blurRadius = 2f
        )
    )

    SubcomposeLayout(modifier = modifier) { constraints ->
        val maxWidthPx = constraints.maxWidth
        val maxHeightPx = constraints.maxHeight

        // Don't render if space is too small
        if (maxWidthPx <= 0 || maxHeightPx <= 0) {
            return@SubcomposeLayout layout(0, 0) {}
        }

        // Binary search for optimal font size
        var low = minFontSize.toPx()
        var high = maxFontSize.toPx()
        var bestSizePx = low

        // Limit iterations to prevent UI thread lock on edge cases
        var iterations = 0
        while (low <= high && iterations < 20) {
            val mid = (low + high) / 2
            val midSp = mid.toSp()
            
            val placeable = subcompose("measure_$iterations") {
                Text(
                    text = text,
                    fontSize = midSp,
                    fontFamily = fontFamily,
                    fontStyle = fontStyle,
                    fontWeight = fontWeight,
                    textAlign = textAlign,
                    lineHeight = midSp * 1.1f, // 1.1 line height for better readability
                    style = style,
                    softWrap = true,
                )
            }[0].measure(Constraints(maxWidth = maxWidthPx))

            if (placeable.height <= maxHeightPx && placeable.width <= maxWidthPx) {
                bestSizePx = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
            iterations++
        }
        
        fontSize.value = bestSizePx.toSp()

        val textPlaceable = subcompose("content") {
            Text(
                text = text,
                fontSize = fontSize.value,
                fontFamily = fontFamily,
                fontStyle = fontStyle,
                fontWeight = fontWeight,
                color = color,
                textAlign = textAlign,
                lineHeight = fontSize.value * 1.1f,
                style = style,
                softWrap = true,
                overflow = TextOverflow.Clip
            )
        }[0].measure(constraints)

        // Center vertically if there is space
        val yOffset = max(0, (constraints.maxHeight - textPlaceable.height) / 2)
        
        layout(constraints.maxWidth, constraints.maxHeight) {
            textPlaceable.place(0, yOffset)
        }
    }
}
