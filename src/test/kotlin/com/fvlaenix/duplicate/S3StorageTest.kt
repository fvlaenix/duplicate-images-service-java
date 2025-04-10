package com.fvlaenix.duplicate

import duplicate.s3.S3Storage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.logging.Logger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageTest {
  private val logger = Logger.getLogger(S3StorageTest::class.java.name)
  private lateinit var s3Storage: S3Storage

  @BeforeAll
  fun setup() {
    logger.info("Setting up LocalStack for S3 testing")

    // Start LocalStack container
    S3TestUtils.startLocalStack()

    // Configure S3Storage to use LocalStack
    S3Storage.setTestingMode(S3TestUtils.createTestS3Properties())

    // Get a configured S3Storage instance
    s3Storage = S3Storage.getInstance()

    logger.info("Test setup complete")
  }

  @AfterAll
  fun tearDown() {
    logger.info("Tearing down test environment")

    // Reset the storage instance
    S3Storage.resetTestingMode()

    // Stop the LocalStack container
    S3TestUtils.stopLocalStack()

    logger.info("Test teardown complete")
  }

  @Test
  fun `test S3 key generation is consistent`() {
    // Test that key generation produces consistent results
    val key1 = s3Storage.generateS3Key("testGroup", "msg123", 1, "test.jpg", 1617246000)
    val key2 = s3Storage.generateS3Key("testGroup", "msg123", 1, "test.jpg", 1617246000)

    assertEquals(key1, key2, "Generated S3 keys should be consistent")
  }

  @Test
  fun `test upload and retrieve image`() {
    // Create a simple test image
    val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.color = Color.RED
    g.fillRect(0, 0, 50, 50)
    g.color = Color.BLUE
    g.fillRect(50, 50, 50, 50)
    g.dispose()

    // Generate a key and upload
    val key = s3Storage.generateS3Key("testGroup", "testMsg", 1, "test.png", 1617246000)
    val uploaded = s3Storage.uploadImage(key, image, "png")

    assertTrue(uploaded, "Image should upload successfully")
    assertTrue(s3Storage.isObjectExists(key), "Image should exist in S3 after upload")

    // Retrieve the image
    val retrieved = s3Storage.getImage(key)

    assertNotNull(retrieved, "Retrieved image should not be null")

    // Basic image comparison (just checking dimensions for simplicity)
    assertEquals(image.width, retrieved!!.width, "Retrieved image should have same width")
    assertEquals(image.height, retrieved.height, "Retrieved image should have same height")
        
    // Sample a few pixels to make sure content is the same
    assertEquals(Color.RED.rgb, retrieved.getRGB(10, 10), "Top-left should be red")
    assertEquals(Color.BLUE.rgb, retrieved.getRGB(75, 75), "Bottom-right should be blue")
  }

  @Test
  fun `test delete image`() {
    // Create a simple test image
    val image = BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.color = Color.GREEN
    g.fillRect(0, 0, 50, 50)
    g.dispose()

    // Generate a key and upload
    val key = s3Storage.generateS3Key("testGroup", "deleteTest", 1, "delete.png", 1617246000)
    s3Storage.uploadImage(key, image, "png")

    // Verify it exists
    assertTrue(s3Storage.isObjectExists(key), "Image should exist before deletion")

    // Delete the image
    val deleted = s3Storage.deleteImage(key)

    assertTrue(deleted, "Image deletion should succeed")
    assertFalse(s3Storage.isObjectExists(key), "Image should not exist after deletion")

    // Try to retrieve - should return null
    val retrieved = s3Storage.getImage(key)
    assertNull(retrieved, "Retrieved image should be null after deletion")
  }

  @Test
  fun `test extension extraction`() {
    assertEquals("jpg", s3Storage.getExtension("image.jpg"))
    assertEquals("png", s3Storage.getExtension("path/to/image.png"))
    assertEquals("jpeg", s3Storage.getExtension("image.with.dots.jpeg"))
    assertEquals("jpg", s3Storage.getExtension("no_extension"), "Should default to jpg")
  }

  @Test
  fun `test timestamp to date string conversion`() {
    // Test with seconds timestamp
    assertEquals("2021-04-01", s3Storage.timestampToDateString(1617246000))
  }
}