package eu.kanade.translation.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PageTranslationHelper {

    companion object {
        /**
         * Merges text blocks that are close to each other or overlapping.
         * This is useful for combining text lines within a speech bubble.
         */
        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            widthThreshold: Int = 100, // Increased threshold
            xThreshold: Int = 50, // Increased threshold
            yThreshold: Int = 50, // Increased threshold
        ): MutableList<TranslationBlock> {
            if (blocks.isEmpty()) return mutableListOf()

            // Filter out blocks with empty text or very small dimensions (likely noise)
            val validBlocks = blocks.filter { it.text.isNotBlank() && it.width > 2 && it.height > 2 }
            if (validBlocks.isEmpty()) return mutableListOf()

            // Sort blocks by Y coordinate to process them top-down, which helps with the logic
            val sortedBlocks = validBlocks.sortedBy { it.y }
            
            val merged = mutableListOf<TranslationBlock>()
            var current = sortedBlocks[0]
            
            for (i in 1 until sortedBlocks.size) {
                val next = sortedBlocks[i]
                if (shouldMergeTextBlock(current, next, widthThreshold, xThreshold, yThreshold)) {
                    current = mergeTextBlock(current, next)
                } else {
                    merged.add(current)
                    current = next
                }
            }
            merged.add(current)
            
            // Run overlap merge after smart merge to catch any remaining heavy overlaps
            return mergeOverlap(merged).toMutableList()
        }

        private fun shouldMergeTextBlock(
            a: TranslationBlock,
            b: TranslationBlock,
            widthThreshold: Int,
            xThreshold: Int,
            yThreshold: Int,
        ): Boolean {
            // Check if widths are similar or if one is significantly smaller (e.g. punctuation or short line)
            // Or if they are just close horizontally
            val isXClose = abs(a.x - b.x) < xThreshold || 
                           abs((a.x + a.width) - (b.x + b.width)) < xThreshold ||
                           (a.x < b.x + b.width && a.x + a.width > b.x) // Horizontal Overlap
            
            // Check vertical proximity. 
            // b.y should be greater than a.y since we sorted, but we check the gap.
            // Gap = b.y - (a.y + a.height)
            // If they overlap vertically, the gap is negative, which is also fine for merging.
            val gap = b.y - (a.y + a.height)
            
            // Use a dynamic threshold based on the height of the blocks (line height approximation)
            val dynamicYThreshold = max(yThreshold.toFloat(), min(a.height, b.height) * 0.8f).toInt()
            val isYClose = gap < dynamicYThreshold

            return isXClose && isYClose
        }

        private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
            val minX = min(a.x, b.x)
            val minY = min(a.y, b.y)
            val maxX = max(a.x + a.width, b.x + b.width)
            val maxY = max(a.y + a.height, b.y + b.height)
            
            val separator = if (a.text.endsWith("-") || b.text.startsWith("-")) "" else "\n"
            val newText = "${a.text}$separator${b.text}"
            
            // For translation, join with newline to preserve structure for translator
            // or space if we assume it's one sentence.
            // Using newline is safer for preserving bubble structure in translation.
            val transSeparator = if (a.translation.isNotBlank() && b.translation.isNotBlank()) "\n" else ""
            val newTranslation = if (a.translation.isNotBlank() || b.translation.isNotBlank()) 
                                    "${a.translation}$transSeparator${b.translation}".trim() 
                                 else ""

            return TranslationBlock(
                text = newText,
                translation = newTranslation,
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = (a.angle + b.angle) / 2,
                symWidth = (a.symWidth + b.symWidth) / 2,
                symHeight = (a.symHeight + b.symHeight) / 2,
            )
        }

        /**
         * Merges Text block which overlap strictly.
         */
        fun mergeOverlap(blocks: List<TranslationBlock>): List<TranslationBlock> {
            if (blocks.isEmpty()) return emptyList()

            var currentBlocks = blocks.toList()
            var hasMerged = true
            
            while (hasMerged) {
                hasMerged = false
                val result = mutableListOf<TranslationBlock>()
                val usedIndices = BooleanArray(currentBlocks.size)

                for (i in currentBlocks.indices) {
                    if (usedIndices[i]) continue
                    
                    var current = currentBlocks[i]
                    
                    for (j in i + 1 until currentBlocks.size) {
                         if (usedIndices[j]) continue
                         val candidate = currentBlocks[j]
                         
                         if (shouldMergeOverlap(current, candidate)) {
                             current = mergeTextBlock(current, candidate)
                             usedIndices[j] = true
                             hasMerged = true
                         }
                    }
                    result.add(current)
                }
                currentBlocks = result
            }
            return currentBlocks
        }

        private fun shouldMergeOverlap(r1: TranslationBlock, r2: TranslationBlock): Boolean {
             // 1. Angle Check: Are they roughly parallel?
            if (abs(r1.angle - r2.angle) > 20) return false

            // 2. Overlap Check: Do their bounding boxes intersect significantly?
            // Expand boxes slightly for intersection check
            val margin = 10f
            val r1Left = r1.x - margin
            val r1Right = r1.x + r1.width + margin
            val r1Top = r1.y - margin
            val r1Bottom = r1.y + r1.height + margin

            val r2Left = r2.x
            val r2Right = r2.x + r2.width
            val r2Top = r2.y
            val r2Bottom = r2.y + r2.height
            
            val intersects = r1Left < r2Right &&
                r1Right > r2Left &&
                r1Top < r2Bottom &&
                r1Bottom > r2Top
            
            if (!intersects) return false
            
            // Calculate Intersection Area to avoid merging things that just barely touch
            val interLeft = max(r1Left, r2Left)
            val interTop = max(r1Top, r2Top)
            val interRight = min(r1Right, r2Right)
            val interBottom = min(r1Bottom, r2Bottom)
            
            val interWidth = max(0f, interRight - interLeft)
            val interHeight = max(0f, interBottom - interTop)
            val interArea = interWidth * interHeight
            
            val r1Area = r1.width * r1.height
            val r2Area = r2.width * r2.height
            val minArea = min(r1Area, r2Area)
            
            // If intersection covers at least 30% of the smaller box, merge
            return interArea > (minArea * 0.30f)
        }
    }
}
