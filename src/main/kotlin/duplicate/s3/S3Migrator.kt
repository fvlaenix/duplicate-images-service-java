package duplicate.s3

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ObjectMetadata
import duplicate.database.ImageConnector
import duplicate.database.ImageHashConnector
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class S3Migrator(
  private val database: Database,
  s3Properties: Properties
) {
  private val logger = Logger.getLogger(S3Migrator::class.java.name)
  private val imageConnector = ImageConnector(database)
  private val imageHashConnector = ImageHashConnector(database)

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

    logger.info("S3 client initialized, region: $region, bucket: $bucketName")
  }

  // Converts timestamp to date string in yyyy-MM-dd format using UTC timezone
  private fun timestampToDateString(timestamp: Long): String {
    // Convert seconds to milliseconds
    val timestampMs = timestamp * 1000
    val instant = Instant.ofEpochMilli(timestampMs)
    val localDate = instant.atZone(ZoneId.of("UTC")).toLocalDate()
    return localDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
  }

  // Gets extension from filename
  private fun getExtension(fileName: String): String {
    return fileName.substringAfterLast('.', "jpg")
  }

  // Checks if object exists in S3
  private fun isObjectExists(key: String): Boolean {
    return try {
      s3Client.doesObjectExist(bucketName, key)
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Error checking if object $key exists in S3", e)
      false
    }
  }

  // Method to get all image IDs
  private fun getAllImageIds(batchSize: Int): List<Long> = transaction(database) {
    val totalCount = imageConnector.getTotalImagesCount()
    logger.info("Total images found: $totalCount")

    val allIds = mutableListOf<Long>()
    var offset = 0

    while (offset < totalCount) {
      val batch = imageConnector.getImageIdsBatch(offset, batchSize)
      if (batch.isEmpty()) break

      allIds.addAll(batch)
      offset += batchSize

      if (offset % 1000 == 0 || offset >= totalCount) {
        logger.info("Loaded $offset/$totalCount image IDs")
      }
    }

    allIds
  }

  // Main migration method
  @OptIn(ExperimentalCoroutinesApi::class)
  fun migrateImagesToS3(batchSize: Int = 100, parallelism: Int = 16) {
    logger.info("Starting image migration to S3")

    val counter = AtomicInteger(0)
    val errorCounter = AtomicInteger(0)
    val skipCounter = AtomicInteger(0)

    // Get all image IDs
    val allImageIds = getAllImageIds(batchSize)
    val totalImages = allImageIds.size

    // Split into batches for parallel processing
    val batches = allImageIds.chunked(batchSize)

    // Create fixed thread pool for processing
    val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)

    runBlocking {
      batches.forEachIndexed { batchIndex, batch ->
        val deferreds = batch.map { imageId ->
          async(dispatcher) {
            processSingleImage(imageId, counter, skipCounter, errorCounter)
          }
        }

        // Wait for all tasks in the current batch to complete
        deferreds.awaitAll()

        // Log progress information after each batch
        val processedCount = (batchIndex + 1) * batchSize
        logger.info(
          "Processed $processedCount/$totalImages images. " +
              "Success: ${counter.get()}, skipped: ${skipCounter.get()}, " +
              "errors: ${errorCounter.get()}"
        )
      }
    }

    logger.info(
      "Migration completed. Total processed: $totalImages images. " +
          "Successfully migrated: ${counter.get()}, skipped: ${skipCounter.get()}, " +
          "errors: ${errorCounter.get()}"
    )
  }

  // Process a single image
  private suspend fun processSingleImage(
    imageId: Long,
    counter: AtomicInteger,
    skipCounter: AtomicInteger,
    errorCounter: AtomicInteger
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      // Get image data in transaction
      val (image, imageHash, imageBytes) = transaction(database) {
        // Get image from database
        val image = imageConnector.findById(imageId)
        if (image == null) {
          logger.log(Level.SEVERE, "Image with ID $imageId not found in Image table")
          return@transaction null
        }

        // Get image hash
        val imageHash = imageHashConnector.getById(imageId)
        if (imageHash == null) {
          logger.log(Level.SEVERE, "Hash for image with ID $imageId not found in ImageHash table")
          return@transaction null
        }

        // Get image bytes
        val extension = getExtension(image.fileName)
        val imageBytes = duplicate.utils.ImageUtils.getByteArray(image.image, extension)

        Triple(image, imageHash, imageBytes)
      } ?: return@withContext false

      // Form path for S3
      val extension = getExtension(image.fileName)
      val date = timestampToDateString(imageHash.timestamp)
      val s3Key = "${image.group}/$date/${image.messageId}/${image.numberInMessage}.$extension"

      // Check if object exists
      if (isObjectExists(s3Key)) {
        if (logger.isLoggable(Level.FINE)) {
          logger.log(Level.FINE, "Image $s3Key already exists in S3, skipping")
        }
        skipCounter.incrementAndGet()
        return@withContext true
      }

      // Upload to S3
      val metadata = ObjectMetadata()
      metadata.contentLength = imageBytes.size.toLong()
      metadata.contentType = "image/$extension"

      val inputStream = ByteArrayInputStream(imageBytes)
      s3Client.putObject(bucketName, s3Key, inputStream, metadata)

      val count = counter.incrementAndGet()
      if (count % 100 == 0) {
        logger.info("Progress: successfully migrated $count images")
      }

      true
    } catch (e: Exception) {
      logger.log(Level.SEVERE, "Error migrating image with ID $imageId", e)
      errorCounter.incrementAndGet()
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

    // Creates instance from properties file
    fun create(database: Database): S3Migrator {
      val logger = Logger.getLogger(S3Migrator::class.java.name)

      val properties = ENV_PROPERTIES?.apply {
        logger.info("Using S3 properties from environment variable")
      } ?: run {
        // Try to use default properties file
        val resourceStream = S3Migrator::class.java.getResourceAsStream("/s3.properties")
          ?: throw IllegalStateException("Can't find environment variable PATH_TO_S3_PROPERTIES and default properties file")

        Properties().apply {
          load(resourceStream)
          logger.info("Using default S3 properties file")
        }
      }

      return S3Migrator(database, properties)
    }
  }
}