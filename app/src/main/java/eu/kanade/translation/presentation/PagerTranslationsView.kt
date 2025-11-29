package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max

class PagerTranslationsView :
    AbstractComposeView {

    private val translation: PageTranslation
    private val font: TranslationFont
    private val fontFamily: FontFamily

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = PageTranslation.EMPTY
        this.font = TranslationFont.ANIME_ACE
        this.fontFamily = if (font.res != null) {
            Font(resId = font.res!!, weight = FontWeight.Bold).toFontFamily()
        } else {
            FontFamily.Default
        }
    }

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        translation: PageTranslation,
        font: TranslationFont? = null,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = translation
        this.font = font ?: TranslationFont.ANIME_ACE
        this.fontFamily = if (this.font.res != null) {
            Font(resId = this.font.res!!, weight = FontWeight.Bold).toFontFamily()
        } else {
            FontFamily.Default
        }
    }

    val scaleState = MutableStateFlow(1f)
    val viewTLState = MutableStateFlow(PointF())

    @Composable
    override fun Content() {
        val viewTL by viewTLState.collectAsState()
        val scale by scaleState.collectAsState()
        
        Box(
            modifier = Modifier
                .absoluteOffset(viewTL.x.pxToDp(), viewTL.y.pxToDp())
                .zIndex(100f),
        ) {
            translation.blocks.forEach { block ->
                val padX = block.symWidth * 0.5f 
                val padY = block.symHeight * 0.5f
                val zoomScale = scale
                
                val bgX = max((block.x - padX / 2) * zoomScale, 0f)
                val bgY = max((block.y - padY / 2) * zoomScale, 0f)
                val bgWidth = (block.width + padX) * zoomScale
                val bgHeight = (block.height + padY) * zoomScale
                
                Box(
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart, true)
                        .offset(bgX.pxToDp(), bgY.pxToDp())
                        .requiredSize(bgWidth.pxToDp(), bgHeight.pxToDp())
                        .background(Color.White, shape = RoundedCornerShape(4.dp))
                        .zIndex(1f)
                )
            }

            translation.blocks.forEach { block ->
                SmartTranslationBlock(
                    modifier = Modifier.zIndex(2f),
                    block = block,
                    scaleFactor = scale,
                    fontFamily = fontFamily,
                )
            }
        }
    }

    fun show() {
        isVisible = true
        ViewCompat.setElevation(this, 100f)
        bringToFront() 
        (parent as? android.view.View)?.invalidate()
    }

    fun hide() {
        isVisible = false
    }
}
