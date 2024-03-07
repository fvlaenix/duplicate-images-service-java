package com.fvlaenix.duplicate

import com.fvlaenix.duplicate.image.ComparingPictures
import com.fvlaenix.duplicate.protobuf.addImageRequest
import com.fvlaenix.duplicate.protobuf.checkImageRequest
import com.fvlaenix.duplicate.utils.ImageUtils
import com.fvlaenix.image.protobuf.Image
import com.fvlaenix.image.protobuf.image
import com.google.protobuf.kotlin.toByteString
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparingMachineTest {

  private fun generateNewImage(number: Int): BufferedImage {
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
  
  private fun toImage(filename: String, image: BufferedImage): Image {
    return image { this.fileName = filename; this.content = ImageUtils.getByteArray(image, "PNG").toByteString() }
  }
  
  @Test
  fun `full graph of dependencies`() = Context.withComparingMachine { comparingMachine ->
    val duplicateImage = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)

    var countOfImages = 0
    repeat(10) { messageId ->
      val imagesInMessage = Random.nextInt(1, 6)
      val epoch = System.currentTimeMillis()
      repeat(imagesInMessage) { numberInMessage ->
        comparingMachine.addImage(
          addImageRequest { 
            this.group = "group-1"
            this.imageInfo = "{messageId:$messageId,numberInMessage:$numberInMessage}"
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
          }).imageInfo.imageInfoCount
        }
      }
      assertEquals(countOfImages * imagesInMessage, comparingResult)
      countOfImages += imagesInMessage
    }
  }

  @Test
  fun `no duplicates test`() = Context.withComparingMachine { comparingMachine ->
    ComparingPictures.TEST_TOLERANCE = 1
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
        comparingMachine.addImage(
          addImageRequest {
            this.group = "group"
            this.imageInfo = "{messageId:$messageId,numberInMessage:$numberInMessage}"
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
          ).imageInfo.imageInfoCount
        }
      }
      assertEquals(0, comparingResult)
    }
  }

  @Test
  fun `random test`() = Context.withComparingMachine { comparingMachine ->
    ComparingPictures.TEST_TOLERANCE = 1
    val images = mutableMapOf<Int, Int>()

    repeat(50) { number ->
      val randomSeed = Random.nextInt(10)
      images[randomSeed] = (images[randomSeed] ?: 0) + 1
      val image = generateNewImage(randomSeed)
      assertTrue(comparingMachine.addImage(addImageRequest {
        this.group = "group-1"
        this.imageInfo = "{messageId:$number}"
        this.image = toImage("file.png", image)
        this.timestamp = 1
      }).isAdded)
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
      ).imageInfo.imageInfoCount
      assertEquals(expected, actualCount)
    }
  }
  
  @Test
  fun `added image have same image info`() = Context.withComparingMachine { comparingMachine -> 
    ComparingPictures.TEST_TOLERANCE = 1
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(1)
    comparingMachine.addImage(addImageRequest { 
      this.group = "1"
      this.imageInfo = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0 
    })
    comparingMachine.addImage(addImageRequest { 
      this.group = "1"
      this.imageInfo = "2"
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
    assertEquals(firstImageSearch.imageInfo.imageInfoList.map { it }, listOf("1"))
    assertEquals(secondImageSearch.imageInfo.imageInfoList.map { it }, listOf("2"))
  }
  
  @Test
  fun `test two images`() = Context.withComparingMachine { comparingMachine ->
    ComparingPictures.TEST_TOLERANCE = 1
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(0)
    comparingMachine.addImage(addImageRequest {
      this.group = "1"
      this.imageInfo = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    comparingMachine.addImage(addImageRequest {
      this.group = "1"
      this.imageInfo = "2"
      this.image = toImage("b.png", secondImage)
      this.timestamp = 0
    })
    val firstImageSearch = comparingMachine.checkImage(checkImageRequest {
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 1
    })
    assertEquals(firstImageSearch.imageInfo.imageInfoList.map { it }.sorted(), listOf("1", "2"))
  }
  
  @Test
  fun `two different groups`() = Context.withComparingMachine { comparingMachine ->
    ComparingPictures.TEST_TOLERANCE = 1
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(0)
    comparingMachine.addImage(addImageRequest {
      this.group = "1"
      this.imageInfo = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    comparingMachine.addImage(addImageRequest {
      this.group = "2"
      this.imageInfo = "2"
      this.image = toImage("b.png", secondImage)
      this.timestamp = 0
    })
    val firstImageSearch = comparingMachine.checkImage(checkImageRequest {
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 1
    })
    assertEquals(firstImageSearch.imageInfo.imageInfoList.map { it }, listOf("1"))
  }

  @Test
  fun `less timestamp`() = Context.withComparingMachine { comparingMachine ->
    ComparingPictures.TEST_TOLERANCE = 1
    val firstImage = generateNewImage(0)
    val secondImage = generateNewImage(0)
    comparingMachine.addImage(addImageRequest {
      this.group = "1"
      this.imageInfo = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    comparingMachine.addImage(addImageRequest {
      this.group = "2"
      this.imageInfo = "2"
      this.image = toImage("b.png", secondImage)
      this.timestamp = 0
    })
    val firstImageSearch = comparingMachine.checkImage(checkImageRequest {
      this.group = "1"
      this.image = toImage("a.png", firstImage)
      this.timestamp = 0
    })
    assertEquals(firstImageSearch.imageInfo.imageInfoList.map { it }, listOf())
  }
}