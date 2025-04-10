package duplicate.s3

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.S3Object
import duplicate.utils.ImageUtils
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

/**
 * Class for managing image storage in S3
 */
class S3Storage private constructor(s3Properties: Properties) {
  private val logger = Logger.getLogger(S3Storage::class.java.name)

  private val s3Client: AmazonS3
  private val bucketName: String

  init {
    // Initialize S3 client
    val accessKey = s3Properties.getProperty("accessKey")
      ?: throw IllegalStateException("accessKey not found in properties")
    val secretKey = s3Properties.getProperty("secretKey")
      ?: throw IllegalStateException("secretKey not found in properties")
    val region = s3Properties.getProperty("region")
      ?: throw IllegalStateException("region not found in properties")
    bucketName = s3Properties.getProperty("bucketName")
      ?: throw IllegalStateException("bucketName not found in properties")

    s3Client = AmazonS3ClientBuilder.standard()
      .withRegion(region)
      .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKey, secretKey)))
      .build()

    logger.info("S3 storage initialized, region: $region, bucket: $bucketName")
  }

  /**
   * Converts timestamp to date string in yyyy-MM-dd format using UTC timezone
   */
  fun timestampToDateString(timestamp: Long): String {
    // Convert seconds to milliseconds
    val timestampMs = timestamp * 1000
    val instant = Instant.ofEpochMilli(timestampMs)
    val localDate = instant.atZone(ZoneId.of("UTC")).toLocalDate()
    return localDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
  }

  /**
   * Gets extension from filename
   */
  fun getExtension(fileName: String): String {
    return fileName.substringAfterLast('.', "jpg")
  }

  /**
   * Generate S3 key for an image
   */
  fun generateS3Key(group: String, messageId: String, numberInMessage: Int, fileName: String, timestamp: Long): String {
    val extension = getExtension(fileName)
    val date = timestampToDateString(timestamp)
    return "${group}/$date/${messageId}/${numberInMessage}.$extension"
  }

  /**
   * Checks if object exists in S3
   */
  fun isObjectExists(s3Key: String): Boolean {
    return try {
      s3Client.doesObjectExist(bucketName, s3Key)
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Error checking if object $s3Key exists in S3", e)
      false
    }
  }

  /**
   * Gets image from S3 with caching
   */
  fun getImage(s3Key: String): BufferedImage? {
    try {
      val s3Object: S3Object = s3Client.getObject(bucketName, s3Key)
      s3Object.use { obj ->
        obj.objectContent.use { content ->
          return ImageIO.read(content)
        }
      }
    } catch (e: Exception) {
      logger.log(Level.SEVERE, "Error getting image from S3: $s3Key", e)
      return null
    }
  }

  /**
   * Uploads image to S3
   */
  fun uploadImage(s3Key: String, image: BufferedImage, extension: String): Boolean {
    try {
      val byteArray = ImageUtils.getByteArray(image, extension)
      val inputStream = ByteArrayInputStream(byteArray)
      val metadata = ObjectMetadata()
      metadata.contentLength = byteArray.size.toLong()
      metadata.contentType = "image/$extension"

      s3Client.putObject(bucketName, s3Key, inputStream, metadata)

      return true
    } catch (e: Exception) {
      logger.log(Level.SEVERE, "Error uploading image to S3: $s3Key", e)
      return false
    }
  }

  /**
   * Deletes image from S3
   */
  fun deleteImage(s3Key: String): Boolean {
    return try {
      s3Client.deleteObject(bucketName, s3Key)

      true
    } catch (e: Exception) {
      logger.log(Level.SEVERE, "Error deleting image from S3: $s3Key", e)
      false
    }
  }

  companion object {
    private val ENV_PROPERTIES = System.getenv("PATH_TO_S3_PROPERTIES")?.let {
      val path = Path(it)
      if (path.notExists()) {
        throw IllegalStateException("Path should be accessible: $path")
      }
      Properties().apply { load(path.inputStream()) }
    }

    // Single instance of S3Storage
    private var instance: S3Storage? = null

    /**
     * Creates or returns the singleton instance
     */
    @Synchronized
    fun getInstance(): S3Storage {
      if (instance == null) {
        val properties = ENV_PROPERTIES ?: run {
          // Try to use default properties file
          val resourceStream = S3Storage::class.java.getResourceAsStream("/s3.properties")
            ?: throw IllegalStateException("Can't find S3 properties file")

          Properties().apply { load(resourceStream) }
        }

        instance = S3Storage(properties)
      }

      return instance!!
    }
  }
}