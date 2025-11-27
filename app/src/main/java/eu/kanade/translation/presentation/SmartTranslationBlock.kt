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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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

    // Padding constants - MUST match those in PagerTranslationsView and WebtoonTranslationsView
    val padX = block.symWidth
    val padY = block.symHeight * 0.75f
    
    val xPx = max((block.x - padX / 2) * scaleFactor, 0.0f)
    val yPx = max((block.y - padY / 2) * scaleFactor, 0.0f)
    
    val width = ((block.width + padX) * scaleFactor).pxToDp()
    val height = ((block.height + padY) * scaleFactor).pxToDp()
    val isVertical = block.angle > 85

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.TopStart, true)
            .offset(xPx.pxToDp(), yPx.pxToDp())
            .requiredSize(width, height)
            .rotate(if (isVertical) 0f else block.angle)
    ) {
        val density = LocalDensity.current
        val fontSize = remember { mutableStateOf(16.sp) }
        
        SubcomposeLayout { constraints ->
            val maxWidthPx = with(density) { width.roundToPx() }
            val maxHeightPx = with(density) { height.roundToPx() }

            // Binary search for optimal font size
            var low = 1
            var high = 150
            var bestSize = low

            while (low <= high) {
                val mid = ((low + high) / 2)
                val textLayoutResult = subcompose(mid.sp) {
                    Text(
                        text = block.translation,
                        fontSize = mid.sp,
                        fontFamily = fontFamily,
                        color = Color.Black,
                        overflow = TextOverflow.Visible,
                        textAlign = TextAlign.Center,
                        maxLines = Int.MAX_VALUE,
                        softWrap = true,
                    )
                }[0].measure(Constraints(maxWidth = maxWidthPx))

                // STRICT check: height must be <= maxHeightPx
                // AND width must be <= maxWidthPx (implicitly handled by measure constraints, 
                // but if softWrap fails or single word is too long, it might overflow width)
                if (textLayoutResult.height <= maxHeightPx) {
                    bestSize = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            fontSize.value = bestSize.sp

            // Measure final layout
            val textPlaceable = subcompose(Unit) {
                Text(
                    text = block.translation,
                    fontSize = fontSize.value,
                    fontFamily = fontFamily,
                    color = Color.Black,
                    softWrap = true,
                    overflow = TextOverflow.Visible, // Should fit due to calculation above
                    textAlign = TextAlign.Center,
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.requiredSize(width, height)
                )
            }[0].measure(constraints)

            layout(textPlaceable.width, textPlaceable.height) {
                textPlaceable.place(0, 0)
            }
        }
    }
}
