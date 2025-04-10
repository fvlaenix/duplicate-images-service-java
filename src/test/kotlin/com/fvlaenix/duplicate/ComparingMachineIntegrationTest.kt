package com.fvlaenix.duplicate

import com.fvlaenix.duplicate.protobuf.addImageRequest
import com.fvlaenix.duplicate.protobuf.checkImageRequest
import com.fvlaenix.duplicate.protobuf.deleteImageRequest
import com.fvlaenix.image.protobuf.image
import com.google.protobuf.kotlin.toByteString
import duplicate.image.ComparingMachine
import duplicate.image.ComparingPictures
import duplicate.s3.S3Storage
import duplicate.utils.ImageUtils
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.*
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComparingMachineIntegrationTest {
  private val logger = Logger.getLogger(ComparingMachineIntegrationTest::class.java.name)
  private lateinit var database: Database
  private lateinit var comparingMachine: ComparingMachine

  @BeforeAll
  fun setup() {
    logger.info("Setting up test environment")

    // Set up LocalStack for S3
    S3TestUtils.startLocalStack()
    val testProperties = S3TestUtils.createTestS3Properties()

    // Configure S3Storage to use test environment
    S3Storage.setTestingMode(testProperties)

    // Set up database (using in-memory H2 database for tests)
    val properties = Properties().apply {
      load(Context::class.java.getResourceAsStream("/database/testDatabase.properties")!!)
    }

    database = Database.connect(
      url = properties.getProperty("url")!!,
      driver = properties.getProperty("driver")!!,
      user = properties.getProperty("user")!!,
      password = properties.getProperty("password")!!
    )

    // Create comparing machine
    comparingMachine = ComparingMachine(database)

    logger.info("Test setup complete")
  }

  @AfterAll
  fun tearDown() {
    logger.info("Tearing down test environment")

    // Reset S3Storage testing mode
    S3Storage.resetTestingMode()

    // Stop the LocalStack container
    S3TestUtils.stopLocalStack()

    logger.info("Test teardown complete")
  }

  /**
   * Helper function to create a simple test image with varying color
   */
  private fun createTestImage(color: Color): BufferedImage {
    val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = color
    g.fillRect(0, 0, 100, 100)
    g.dispose()
    return image
  }

  @Test
  fun `test add and retrieve image`() {
    // Create test image (red)
    val image1 = createTestImage(Color.RED)

    // Add image
    val addResponse = comparingMachine.addImageWithCheck(addImageRequest {
      group = "testGroup"
      messageId = "test1"
      numberInMessage = 1
      additionalInfo = "Test image 1"
      image = image {
        fileName = "test1.png"
        content = ImageUtils.getByteArray(image1, "png").toByteString()
      }
      timestamp = System.currentTimeMillis() / 1000
    })

    // Check response
    assertTrue(addResponse.hasResponseOk(), "Should have successful response")
    assertTrue(addResponse.responseOk.isAdded, "Image should be added")
    assertEquals(0, addResponse.responseOk.imageInfo.imagesCount, "Should have no similar images")

    // Now check if exists
    val existsResponse = comparingMachine.existsImage(com.fvlaenix.duplicate.protobuf.existsImageRequest {
      group = "testGroup"
      messageId = "test1"
      numberInMessage = 1
    })

    assertTrue(existsResponse.isExists, "Image should exist after adding")
  }

  @Test
  fun `test duplicate detection`() {
    // Create similar images (both blue)
    val image1 = createTestImage(Color.BLUE)
    val image2 = createTestImage(Color.BLUE)

    // Set test tolerance to ensure they match
    ComparingPictures.TEST_TOLERANCE = 10

    // Add first image
    comparingMachine.addImageWithCheck(addImageRequest {
      group = "testDupes"
      messageId = "dupe1"
      numberInMessage = 1
      additionalInfo = "Duplicate test 1"
      image = image {
        fileName = "dupe1.png"
        content = ImageUtils.getByteArray(image1, "png").toByteString()
      }
      timestamp = System.currentTimeMillis() / 1000
    })

    // Check second image for duplicates
    val checkResponse = comparingMachine.checkImage(checkImageRequest {
      group = "testDupes"
      image = image {
        fileName = "dupe2.png"
        content = ImageUtils.getByteArray(image2, "png").toByteString()
      }
      timestamp = System.currentTimeMillis() / 1000 + 10  // Later timestamp
    })

    // We should find the first image as a duplicate
    assertEquals(1, checkResponse.imageInfo.imagesCount, "Should find one duplicate")
    assertEquals("dupe1", checkResponse.imageInfo.imagesList[0].messageId, "Duplicate should be first image")
  }

  @Test
  fun `test add and delete image`() {
    // Create test image (green)
    val image = createTestImage(Color.GREEN)

    // Add image
    comparingMachine.addImageWithCheck(addImageRequest {
      group = "deleteTest"
      messageId = "toDelete"
      numberInMessage = 1
      additionalInfo = "To be deleted"
      this.image = image {
        fileName = "delete.png"
        content = ImageUtils.getByteArray(image, "png").toByteString()
      }
      timestamp = System.currentTimeMillis() / 1000
    })

    // Verify it exists
    val existsBeforeDelete = comparingMachine.existsImage(com.fvlaenix.duplicate.protobuf.existsImageRequest {
      group = "deleteTest"
      messageId = "toDelete"
      numberInMessage = 1
    })

    assertTrue(existsBeforeDelete.isExists, "Image should exist before deletion")

    // Delete the image
    val deleteResponse = comparingMachine.deleteImage(deleteImageRequest {
      messageId = "toDelete"
      numberInMessage = 1
    })

    assertTrue(deleteResponse.isDeleted, "Delete operation should succeed")

    // Verify it no longer exists
    val existsAfterDelete = comparingMachine.existsImage(com.fvlaenix.duplicate.protobuf.existsImageRequest {
      group = "deleteTest"
      messageId = "toDelete"
      numberInMessage = 1
    })

    assertFalse(existsAfterDelete.isExists, "Image should not exist after deletion")
  }
}