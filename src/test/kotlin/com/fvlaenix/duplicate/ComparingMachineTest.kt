package com.fvlaenix.duplicate

import com.fvlaenix.duplicate.protobuf.addImageRequest
import com.fvlaenix.duplicate.protobuf.checkImageRequest
import duplicate.utils.ImageUtils
import com.fvlaenix.image.protobuf.Image
import com.fvlaenix.image.protobuf.image
import com.google.protobuf.kotlin.toByteString
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.random.Random

class ComparingMachineTest {

  private fun generateNewImage(number: Int): BufferedImage {
    val newImage = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    if (number >= 255) {
      throw IllegalStateException("Can't generate same images because color is over")
    }
    newImage.setRGB(0, 0, Color(number, number, number).rgb)
    return newImage
  }
  
  private fun toImage(filename: String, image: BufferedImage): Image {
    return image { this.fileName = filename; this.content = ImageUtils.getByteArray(image, "PNG").toByteString() }
  }
  
  @Test
  fun `full graph of dependencies`() = Context.withComparingMachine { comparingMachine ->
    val duplicateImage = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)

    var countOfImages = 0
    repeat(10) { messageId ->
      val imagesInMessage = Random.nextInt(1, 6)
      val epoch = System.currentTimeMillis()
      repeat(imagesInMessage) { numberInMessage ->
        comparingMachine.addImageWithCheck(
          addImageRequest { 
            this.group = "group-1"
            this.messageId = messageId.toString()
            this.numberInMessage = numberInMessage
            this.image = image { this.fileName = "file.png"; this.content = ImageUtils.getByteArray(duplicateImage, "PNG").toByteString() }
            this.timestamp = epoch
          }
        )
      }

      val comparingResult = runBlocking {
        (1..imagesInMessage).sumOf {
          comparingMachine.checkImage(checkImageRequest { 
            this.group = "group-1"
            this.image = image { this.fileName = "file.png"; this.content = ImageUtils.getByteArray(duplicateImage, "PNG").toByteString() }
            this.timestamp = epoch
          }).imageInfo.imagesCount
        }
      }
      assertEquals(countOfImages * imagesInMessage, comparingResult)
      countOfImages += imagesInMessage
    }
  }

  @Test
  fun `no duplicates test`() = Context.withComparingMachine { comparingMachine ->
    var imageCount = 0
    fun generateNewImage(): BufferedImage {
      val image = generateNewImage(imageCount)
      imageCount++
      return image
    }

    repeat(10) { messageId ->
      val imagesInMessage = Random.nextInt(1, 5)
      val epoch = System.currentTimeMillis()
      val images = (1..imagesInMessage).map { numberInMessage ->
        val image = generateNewImage()
        comparingMachine.addImageWithCheck(
          addImageRequest {
            this.group = "group"
            this.messageId = messageId.toString()
            this.numberInMessage = numberInMessage
            this.image = toImage("file.png", image)
            this.timestamp = epoch
          }
        )
        image
      }
      val comparingResult = runBlocking {
        images.sumOf { image ->
          comparingMachine.checkImage(
            checkImageRequest {
              this.group = "group"
              this.image = toImage("file.png", image)
              this.timestamp = epoch
            }
          ).imageInfo.imagesCount
        }
      }
      assertEquals(0, comparingResult)
    }
  }

  @Test
  fun `random test`() = Context.withComparingMachine { comparingMachine ->
    val images = mutableMapOf<Int, Int>()

    repeat(50) { number ->
      val randomSeed = Random.nextInt(10)
      images[randomSeed] = (images[randomSeed] ?: 0) + 1
      val image = generateNewImage(randomSeed)
      val resultAdding = comparingMachine.addImageWithCheck(addImageRequest {
        this.group = "group-1"
        this.messageId = "$number"
        this.numberInMessage = 0
        this.additionalInfo = ""
        this.image = toImage("file.png", image)
        this.timestamp = 1
      })
      assertTrue(resultAdding.responseOk.isAdded)
    }

    repeat(50) {
      val randomSeed = Random.nextInt(10)
      val expected = (images[randomSeed] ?: 0)
      val image = generateNewImage(randomSeed)
      val actualCount = comparingMachine.checkImage(
        checkImageRequest {
          this.group = "group-1"
          this.image = toImage("file.png", image)
          this.timestamp = 2
        }
      ).imageInfo.imagesCount
      assertEquals(expected, actualCount)
    }
  }
  
  @Test
  fun `added image have same image info`() = Context.withComparingMachine { comparingMachine ->
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(1)
    comparingMachine.addImageWithCheck(addImageRequest { 
      this.group = "1"
      this.messageId = "1"
      this.numberInMessage = 0
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0 
    })
    comparingMachine.addImageWithCheck(addImageRequest { 
      this.group = "1"
      this.messageId = "2"
      this.numberInMessage = 0
      this.image = toImage("b.png", secondImage)
      this.timestamp = 0
    })
    val firstImageSearch = comparingMachine.checkImage(checkImageRequest { 
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 1
    })
    val secondImageSearch = comparingMachine.checkImage(checkImageRequest { 
      this.group = "1"
      this.image = toImage("b.png", secondImage)
      this.timestamp = 1
    })
    assertEquals(listOf("1"), firstImageSearch.imageInfo.imagesList.map { it.messageId })
    assertEquals(listOf("2"), secondImageSearch.imageInfo.imagesList.map { it.messageId })
  }
  
  @Test
  fun `test two images`() = Context.withComparingMachine { comparingMachine ->
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(0)
    comparingMachine.addImageWithCheck(addImageRequest {
      this.group = "1"
      this.messageId = "1"
      this.numberInMessage = 0
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    comparingMachine.addImageWithCheck(addImageRequest {
      this.group = "1"
      this.messageId = "2"
      this.numberInMessage = 0
      this.image = toImage("b.png", secondImage)
      this.timestamp = 0
    })
    val firstImageSearch = comparingMachine.checkImage(checkImageRequest {
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 1
    })
    assertEquals(firstImageSearch.imageInfo.imagesList.map { it.messageId }.sorted(), listOf("1", "2"))
  }
  
  @Test
  fun `two different groups`() = Context.withComparingMachine { comparingMachine ->
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(0)
    comparingMachine.addImageWithCheck(addImageRequest {
      this.group = "1"
      this.messageId = "1"
      this.numberInMessage = 0
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    comparingMachine.addImageWithCheck(addImageRequest {
      this.group = "2"
      this.messageId = "2"
      this.numberInMessage = 0
      this.image = toImage("b.png", secondImage)
      this.timestamp = 0
    })
    val firstImageSearch = comparingMachine.checkImage(checkImageRequest {
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 1
    })
    assertEquals(firstImageSearch.imageInfo.imagesList.map { it.messageId }, listOf("1"))
  }

  @Test
  fun `less timestamp`() = Context.withComparingMachine { comparingMachine ->
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(0)
    comparingMachine.addImageWithCheck(addImageRequest {
      this.group = "1"
      this.messageId = "1"
      this.numberInMessage = 0
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    comparingMachine.addImageWithCheck(addImageRequest {
      this.group = "2"
      this.messageId = "2"
      this.numberInMessage = 0
      this.image = toImage("b.png", secondImage)
      this.timestamp = 0
    })
    val firstImageSearch = comparingMachine.checkImage(checkImageRequest {
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    assertEquals(firstImageSearch.imageInfo.imagesList.map { it.messageId }, listOf<String>())
  }

  @Test
  fun `additional info is saved`() = Context.withComparingMachine { comparingMachine ->
    val firstImage = generateNewImage(0)
    comparingMachine.addImageWithCheck(addImageRequest {
      this.group = "1"
      this.messageId = "1"
      this.numberInMessage = 0
      this.image = toImage("a.png", firstImage)
      this.additionalInfo = ":abracadabra:"
      this.timestamp = 0
    })
    val checkResult = comparingMachine.checkImage(checkImageRequest {
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 2
    })
    assertEquals(":abracadabra:", checkResult.imageInfo.imagesList[0].additionalInfo)
  }
}