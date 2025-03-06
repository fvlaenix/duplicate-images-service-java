package com.fvlaenix.duplicate

import duplicate.utils.IndicesUtils
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndicesTest {
  @Test
  fun `simple test`() {
    var iter = 0
    val table = MutableList(8) { MutableList(8) { iter++ } }

    val indicies = IndicesUtils.getIndices()
    val prototype = listOf(
      listOf(0, 4),
      listOf(1, 5),
      listOf(2, 6),
      listOf(3, 7),
      listOf(8, 12),
      listOf(9, 13),
      listOf(10, 14),
      listOf(11, 15)
    )
    indicies.forEachIndexed { index, indices ->
      val captured = indices.translateList(table)
      captured.forEach {
        assertTrue(prototype[index].contains(it % 16), "While trying get indecies by number $index. Got $captured")
      }
    }
  }
}