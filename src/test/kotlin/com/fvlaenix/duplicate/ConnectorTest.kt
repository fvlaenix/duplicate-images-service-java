package com.fvlaenix.duplicate

import com.fvlaenix.duplicate.image.ComparingPictures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectorTest {
  @Test
  fun `full graph of dependencies`() = Context.withImageContext { connector ->
    val duplicateImage = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
    
    var countOfImages = 0
    repeat(20) { messageId ->
      val imagesInMessage = Random.nextInt(1, 6)
      val epoch = System.currentTimeMillis()
      repeat(imagesInMessage) { numberInMessage ->
        connector.addImage(
          "group-1",
          "{messageId:$messageId,numberInMessage:$numberInMessage}",
          duplicateImage,
          "file.jpg",
          epoch
        )
      }

      val comparingResult = runBlocking {
        (1..imagesInMessage).sumOf {
          connector.getSimilarImages(
            duplicateImage,
            "group-1",
            epoch
          ).size
        }
      }
      assertEquals(countOfImages * imagesInMessage, comparingResult)
      countOfImages += imagesInMessage
    }
  }
  
  @Test
  fun `no duplicates test`() = Context.withImageContext { imageConnector -> 
    ComparingPictures.TEST_TOLERANCE = 1
    var imageCount = 0
    fun generateNewImage(): BufferedImage {
      val newImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
      var imageDivider = imageCount
      val r = imageDivider % 255
      imageDivider /= 255
      val g = imageDivider % 255
      imageDivider /= 255
      val b = imageDivider % 255
      imageDivider /= 255
      if (imageDivider != 0) {
        throw IllegalStateException("Can't generate same images because color is over")
      }
      newImage.setRGB(0, 0, Color(r, g, b).rgb)
      imageCount++
      return newImage
    }
    
    repeat(30) { messageId ->
      val imagesInMessage = Random.nextInt(1, 10)
      val epoch = System.currentTimeMillis()
      val images = (1..imagesInMessage).map { numberInMessage ->
        val image = generateNewImage()
        imageConnector.addImage(
          "group",
          "{messageId:$messageId,numberInMessage:$numberInMessage}",
          image,
          "file.jpg",
          epoch
        )
        image
      }
      val comparingResult = runBlocking { 
        images.sumOf { image ->
          imageConnector.getSimilarImages(image, "group", epoch).size
        }
      }
      assertEquals(0, comparingResult)
    }
  }
  
  @Test
  fun `random test`() = Context.withImageContext { imageConnector ->
    ComparingPictures.TEST_TOLERANCE = 1
    val images = mutableMapOf<Int, Int>()
    
    fun generateNewImage(number: Int): BufferedImage {
      val newImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
      var imageDivider = number
      val r = imageDivider % 255
      imageDivider /= 255
      val g = imageDivider % 255
      imageDivider /= 255
      val b = imageDivider % 255
      imageDivider /= 255
      if (imageDivider != 0) {
        throw IllegalStateException("Can't generate same images because color is over")
      }
      newImage.setRGB(0, 0, Color(r, g, b).rgb)
      return newImage
    }
    
    repeat(50) { number ->
      val randomSeed = Random.nextInt(10)
      images[randomSeed] = (images[randomSeed] ?: 0) + 1
      val image = generateNewImage(randomSeed)
      assertTrue(imageConnector.addImage(
        "group",
        "messageId:$number",
        image,
        "File.png",
        1
      ))
    }
    
    repeat(50) {
      val randomSeed = Random.nextInt(10)
      val expected = (images[randomSeed] ?: 0)
      val image = generateNewImage(randomSeed)
      val actualCount = imageConnector.getSimilarImages(
        image,
        "group",
        2
      ).size
      assertEquals(expected, actualCount)
    }
  }
  
  @Test
  fun `two different pictures, but same by first pixel`() = Context.withImageContext { imageConnector ->
    val firstImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).apply { this.setRGB(0, 1, Color.BLACK.rgb) }
    val secondImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
    
    imageConnector.addImage(
      "gr",
      "1",
      firstImage,
      "1.png",
      0
    )
    val simular = imageConnector.getSimilarImages(
      secondImage,
      "gr",
      1
    ).size
    assertEquals(1, simular)
  }
}