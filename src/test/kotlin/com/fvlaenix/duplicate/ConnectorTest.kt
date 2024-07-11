package com.fvlaenix.duplicate

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.random.Random

class ConnectorTest {
  @Test
  fun `full graph of dependencies`() = Context.withImageHashContext { connector ->
    val duplicateImage = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
    
    var countOfImages = 0
    var imageId = 0L
    repeat(20) {
      val imagesInMessage = Random.nextInt(1, 6)
      val epoch = System.currentTimeMillis()
      repeat(imagesInMessage) {
        connector.addImageWithCheck(
          id = imageId++,
          group = "group-1",
          timestamp = epoch,
          image = duplicateImage
        )
      }

      val comparingResult = runBlocking {
        (1..imagesInMessage).sumOf {
          connector.imageCheck(
            group = "group-1",
            timestamp = epoch,
            image = duplicateImage
          ).size
        }
      }
      assertEquals(countOfImages * imagesInMessage, comparingResult)
      countOfImages += imagesInMessage
    }
  }
  
  @Test
  fun `no duplicates test`() = Context.withImageHashContext { imageConnector ->
    var imageCount = 0
    fun generateNewImage(): BufferedImage {
      val newImage = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
      val r = imageCount
      val g = imageCount
      val b = imageCount
      if (imageCount >= 255) {
        throw IllegalStateException("Can't generate same images because color is over")
      }
      newImage.setRGB(0, 0, Color(r, g, b).rgb)
      imageCount++
      return newImage
    }

    var imageId = 0L

    repeat(20) { messageId ->
      val imagesInMessage = Random.nextInt(1, 10)
      val epoch = System.currentTimeMillis()
      val images = (1..imagesInMessage).map { numberInMessage ->
        val image = generateNewImage()
        imageConnector.addImageWithCheck(
          id = imageId++,
          group = "group",
          timestamp = epoch,
          image = image,
        )
        image
      }
      val comparingResult = runBlocking { 
        images.sumOf { image ->
          imageConnector.imageCheck(group = "group", timestamp = epoch, image = image).size
        }
      }
      assertEquals(0, comparingResult)
    }
  }
  
  @Test
  fun `random test`() = Context.withImageHashContext { imageConnector ->
    val images = mutableMapOf<Int, Int>()

    fun generateNewImage(number: Int): BufferedImage {
      val newImage = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
      if (number >= 255) {
        throw IllegalStateException("Can't generate same images because color is over")
      }
      newImage.setRGB(0, 0, Color(number, number, number).rgb)
      return newImage
    }

    var imageId = 0L
    repeat(50) { number ->
      val randomSeed = Random.nextInt(10)
      images[randomSeed] = (images[randomSeed] ?: 0) + 1
      val image = generateNewImage(randomSeed)
      assertTrue(imageConnector.addImageWithCheck(
        id = imageId++,
        group = "group",
        timestamp = 1,
        image = image,
      ).isAdded)
    }
    
    repeat(50) {
      val randomSeed = Random.nextInt(10)
      val expected = (images[randomSeed] ?: 0)
      val image = generateNewImage(randomSeed)
      val actualCount = imageConnector.imageCheck(
        group = "group",
        timestamp = 2,
        image = image
      ).size
      assertEquals(expected, actualCount)
    }
  }
  
  @Test
  fun `two different pictures, but same by first pixel`() = Context.withImageHashContext { imageConnector ->
    val firstImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).apply { this.setRGB(0, 1, Color.BLACK.rgb) }
    val secondImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)

    imageConnector.addImageWithCheck(
      id = 0,
      group = "gr",
      timestamp = 0,
      image = firstImage
    )
    val simular = imageConnector.imageCheck(
      group = "gr",
      timestamp = 1,
      image = secondImage
    ).size
    assertEquals(1, simular)
  }
  
  @Test
  fun `delete really deletes picture`() = Context.withImageHashContext { imageConnector ->
    val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
    val id = 1L
    imageConnector.addImageWithCheck(
      id = id,
      group = "g",
      timestamp = 0,
      image = image
    )
    assertEquals(1, imageConnector.imageCheck("g", 1, image).size)
    imageConnector.deleteById(id)
    assertEquals(0, imageConnector.imageCheck("g", 1, image).size)
  }
}