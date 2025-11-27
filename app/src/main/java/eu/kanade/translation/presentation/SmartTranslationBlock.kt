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

    // Adjusted Padding: 
    // Reduced significantly to prevent overlapping.
    // 'padX' is now just a fraction of symWidth to add breathing room without expansion.
    // 'padY' is also reduced.
    val padX = block.symWidth * 0.5f 
    val padY = block.symHeight * 0.5f
    
    // Adjusted Coordinates:
    // Centering logic adjustment. 
    // The previous TopStart logic with offset assumed (x,y) is top-left of the bounding box including padding.
    // If we add padding, we must subtract half of it from x/y to center the new larger box over the old one.
    // However, to fix "below/above" issues, we need to trust the original bbox more.
    // Let's stick closer to the original block.x and block.y without aggressive padding offsets.
    
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
                        lineHeight = mid.sp, // Explicitly set line height to match font size for tighter fit
                    )
                }[0].measure(Constraints(maxWidth = maxWidthPx))

                // Added tolerance 1.1f to allow slightly more text if it fits visually
                if (textLayoutResult.height <= maxHeightPx * 1.1f) {
                    bestSize = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            fontSize.value = bestSize.sp

            val textPlaceable = subcompose(Unit) {
                Text(
                    text = block.translation,
                    fontSize = fontSize.value,
                    fontFamily = fontFamily,
                    color = Color.Black,
                    softWrap = true,
                    overflow = TextOverflow.Visible,
                    textAlign = TextAlign.Center,
                    maxLines = Int.MAX_VALUE,
                    lineHeight = fontSize.value,
                    modifier = Modifier.requiredSize(width, height) // Force size match
                )
            }[0].measure(constraints)

            // Center the text vertically if there's extra space, though textAlign handles horizontal.
            // Box alignment handles this, but explicit placement is safer.
            val yOffset = (constraints.maxHeight - textPlaceable.height) / 2
            
            layout(textPlaceable.width, textPlaceable.height) {
                textPlaceable.place(0, yOffset.coerceAtLeast(0))
            }
        }
    }
}
