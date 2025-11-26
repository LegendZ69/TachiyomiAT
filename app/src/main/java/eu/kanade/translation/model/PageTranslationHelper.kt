package eu.kanade.translation.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PageTranslationHelper {

    companion object {
        /**
         * Merges Text block which overlap
         * this doesn't mutate the original blocks
         */
        fun mergeOverlap(blocks: List<TranslationBlock>): List<TranslationBlock> {
            if (blocks.isEmpty()) return emptyList()

            // We need to keep trying to merge until no more merges occur
            var currentBlocks = blocks.toList()
            var hasMerged = true
            
            while (hasMerged) {
                hasMerged = false
                val result = mutableListOf<TranslationBlock>()
                val usedIndices = mutableSetOf<Int>()

                for (i in currentBlocks.indices) {
                    if (i in usedIndices) continue
                    
                    var current = currentBlocks[i]
                    var mergedThisPass = false
                    
                    for (j in i + 1 until currentBlocks.size) {
                         if (j in usedIndices) continue
                         val candidate = currentBlocks[j]
                         
                         if (shouldMerge(current, candidate)) {
                             current = mergeBlocks(current, candidate)
                             usedIndices.add(j)
                             mergedThisPass = true
                             hasMerged = true
                         }
                    }
                    result.add(current)
                }
                currentBlocks = result
            }
            return currentBlocks
        }

        private fun mergeBlocks(r1: TranslationBlock, r2: TranslationBlock): TranslationBlock {
            val minX = min(r1.x, r2.x)
            val minY = min(r1.y, r2.y)
            val maxX = max(r1.x + r1.width, r2.x + r2.width)
            val maxY = max(r1.y + r1.height, r2.y + r2.height)

            return TranslationBlock(
                text = "${r1.text}\n${r2.text}",
                translation = if(r1.translation.isNotBlank() && r2.translation.isNotBlank()) "${r1.translation}\n${r2.translation}" else "",
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = (r1.angle + r2.angle) / 2,
                symWidth = (r1.symWidth + r2.symWidth) / 2, // Average symbol width
                symHeight = (r1.symHeight + r2.symHeight) / 2, // Average symbol height
            )
        }

        // Checks if two block overlap each other and are in same orientation
        private fun shouldMerge(r1: TranslationBlock, r2: TranslationBlock): Boolean {
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
