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
            widthThreshold: Int = 50,
            xThreshold: Int = 30,
            yThreshold: Int = 30,
        ): MutableList<TranslationBlock> {
            if (blocks.isEmpty()) return mutableListOf()

            // Sort blocks by Y coordinate to process them top-down, which helps with the logic
            val sortedBlocks = blocks.sortedBy { it.y }
            
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
            
            // Optional: Run overlap merge after smart merge to catch any remaining heavy overlaps
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
            // But usually we want to merge lines in a bubble which have similar widths or are centered.
            val isWidthSimilar = (b.width < a.width) || (abs(a.width - b.width) < widthThreshold)
            
            // Check horizontal alignment (X coordinate)
            val isXClose = abs(a.x - b.x) < xThreshold
            
            // Check vertical proximity. 
            // b.y should be greater than a.y since we sorted, but we check the gap.
            // Gap = b.y - (a.y + a.height)
            // If they overlap vertically, the gap is negative, which is also fine for merging.
            val gap = b.y - (a.y + a.height)
            val isYClose = gap < yThreshold

            return isWidthSimilar && isXClose && isYClose
        }

        private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
            val minX = min(a.x, b.x)
            val minY = min(a.y, b.y)
            val maxX = max(a.x + a.width, b.x + b.width)
            val maxY = max(a.y + a.height, b.y + b.height)

            return TranslationBlock(
                text = "${a.text}\n${b.text}",
                translation = if (a.translation.isNotBlank() && b.translation.isNotBlank()) "${a.translation}\n${b.translation}" else "",
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

        // Checks if two block overlap each other and are in same orientation
        private fun shouldMergeOverlap(r1: TranslationBlock, r2: TranslationBlock): Boolean {
             // 1. Angle Check: Are they roughly parallel?
            if (abs(r1.angle - r2.angle) > 10) return false

            // 2. Overlap Check: Do their bounding boxes intersect?
            val intersects = r1.x < (r2.x + r2.width) &&
                (r1.x + r1.width) > r2.x &&
                r1.y < (r2.y + r2.height) &&
                (r1.y + r1.height) > r2.y
            
            return intersects
        }
    }
}
