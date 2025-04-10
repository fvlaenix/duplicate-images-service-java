package duplicate.image

import com.fvlaenix.duplicate.protobuf.*
import com.fvlaenix.duplicate.protobuf.CheckImageResponseImagesInfoKt.checkImageResponseImageInfo
import com.fvlaenix.image.protobuf.Image
import duplicate.database.Connector
import duplicate.database.DuplicateInfoConnector
import duplicate.database.ImageConnector
import duplicate.database.ImageHashConnector
import duplicate.s3.S3Storage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.Database
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.IIOException
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.notExists
import kotlin.math.min

private val LOGGER: Logger = Logger.getLogger(ComparingMachine::class.java.simpleName)

private val ENV_PROPERTIES = System.getenv("PATH_TO_COMPARING_PROPERTIES")?.let {
  val path = Path(it)
  if (path.notExists()) {
    throw IllegalStateException("Path should be accessible: $path")
  }
  Properties().apply { load(path.inputStream()) }
}

private val RESERVE_PROPERTIES = Connector::class.java.getResourceAsStream("/comparing.properties")?.let {
  Properties().apply { load(it) }
}

private val PROPERTIES = ENV_PROPERTIES?.apply { LOGGER.info("Using env properties") }
  ?: RESERVE_PROPERTIES?.apply { LOGGER.info("Using git properties") }
  ?: throw IllegalStateException("Can't find env path ${System.getenv("PATH_TO_COMPARING_PROPERTIES")} and local path")

private val WIDTH = PROPERTIES.getProperty("width")?.toInt() ?: throw IllegalStateException("width should exists in properties")
private val HEIGHT = PROPERTIES.getProperty("height")?.toInt() ?: throw IllegalStateException("height should exists in properties")

class ComparingMachine(database: Database) {

  private val imageConnector = ImageConnector(database)
  private val imageHashConnector = ImageHashConnector(database)
  private val duplicateInfoConnector = DuplicateInfoConnector(database)
  private val s3Storage = S3Storage.getInstance()

  companion object {
    // it is const, but you can change it between launches anyway
    val SIZE_MAX_WIDTH: Int? = if (WIDTH <= 0) null else WIDTH
    val SIZE_MAX_HEIGHT: Int? = if (HEIGHT <= 0) null else HEIGHT

    private const val COMPARING_COUNT = 32

    @OptIn(DelicateCoroutinesApi::class)
    val newThreadContext = newFixedThreadPoolContext(16, "Comparing machine context")

    init {
      if (SIZE_MAX_WIDTH != null && SIZE_MAX_HEIGHT != null) {
        throw IllegalStateException("Can't be both max")
      }
    }
  }

  private fun readImage(image: Image): BufferedImage? {
    val byteString = image.content
    val fileName = image.fileName
    val bufferedImage = try {
      byteString.toByteArray().inputStream().use { stream ->
        ImageIO.read(stream)
      }
    } catch (e: IIOException) {
      LOGGER.log(Level.SEVERE, "Exception while trying read image with name $fileName", e)
      return null
    }
    if (bufferedImage == null) {
      LOGGER.log(Level.SEVERE, "Can't read image with data $fileName")
      return null
    }
    val maxWidth = SIZE_MAX_WIDTH
    val maxHeight = SIZE_MAX_HEIGHT
    if (maxWidth != null && bufferedImage.width > maxWidth) {
      LOGGER.log(Level.WARNING, "Can't read image with incompatible size: width needed: $maxWidth. Found: ${bufferedImage.width}")
      return Thumbnails.of(bufferedImage).width(maxWidth).asBufferedImage()
    }
    if (maxHeight != null && bufferedImage.height > maxHeight) {
      LOGGER.log(Level.WARNING, "Can't read image with incompatible size: height needed: $maxHeight. Found: ${bufferedImage.height}")
      return Thumbnails.of(bufferedImage).height(maxHeight).asBufferedImage()
    }
    return bufferedImage
  }

  fun addImageWithCheck(request: AddImageRequest): AddImageResponse {
    LOGGER.log(Level.FINE, "Got addImageRequest: ${request.messageId}-${request.numberInMessage}")
    val image = readImage(request.image) ?: return addImageResponse {
      this.error = "Can't read image with id ${request.messageId}-${request.numberInMessage}"
    }

    // Store metadata in database (but not the image itself)
    val imageId = imageConnector.withConnect {
      val ids = imageConnector.getId(request.messageId, request.numberInMessage)
      if (ids != null) return@withConnect ids

      imageConnector.new(
        group = request.group,
        messageId = request.messageId,
        numberInMessage = request.numberInMessage,
        additionalInfo = request.additionalInfo,
        fileName = request.image.fileName
      )
    }

    // Add image hash to database
    val added = imageHashConnector.addImageWithCheck(
      id = imageId,
      group = request.group,
      timestamp = request.timestamp,
      image = image
    )

    // Store the actual image in S3
    val s3Key = s3Storage.generateS3Key(
      group = request.group,
      messageId = request.messageId,
      numberInMessage = request.numberInMessage,
      fileName = request.image.fileName,
      timestamp = request.timestamp
    )

    val success = s3Storage.uploadImage(s3Key, image, s3Storage.getExtension(request.image.fileName))
    if (!success) {
      LOGGER.log(Level.WARNING, "Failed to upload image to S3: $s3Key")
    } else {
      LOGGER.log(Level.FINE, "Successfully uploaded image to S3: $s3Key")
    }

    val checkResult = checkImageCandidates(image, added.similarIds)
    checkResult.forEach { original ->
      duplicateInfoConnector.add(
        request.group,
        original.id,
        imageId,
        original.info.level
      )
    }

    return addImageResponse {
      this.responseOk = addImageResponseOk {
        this.isAdded = added.isAdded
        this.imageInfo = CheckImageResponseImagesInfo.newBuilder().addAllImages(checkResult.map { it.info }).build()
      }
    }
  }

  fun existsImage(request: ExistsImageRequest): ExistsImageResponse {
    LOGGER.log(Level.FINE, "Got ExistsImageRequest: ${request.messageId}-${request.numberInMessage}")
    return existsImageResponse {
      this.isExists = imageConnector.getId(request.messageId, request.numberInMessage) != null
    }
  }

  class ResultCheck(
    val id: Long,
    val info: CheckImageResponseImagesInfo.CheckImageResponseImageInfo
  )

  private fun findImageById(id: Long): duplicate.image.Image? {
    // Get metadata from database
    val metadata = imageConnector.getMetadataById(id) ?: return null

    // Get timestamp from hash table
    val imageHash = imageHashConnector.getById(id) ?: return null

    // Build S3 key and fetch image from S3
    val s3Key = s3Storage.generateS3Key(
      group = metadata.group,
      messageId = metadata.messageId,
      numberInMessage = metadata.numberInMessage,
      fileName = metadata.fileName,
      timestamp = imageHash.timestamp
    )

    val image = s3Storage.getImage(s3Key) ?: return null

    // Create complete Image object
    return Image(
      id = metadata.id,
      group = metadata.group,
      messageId = metadata.messageId,
      numberInMessage = metadata.numberInMessage,
      additionalInfo = metadata.additionalInfo,
      fileName = metadata.fileName,
      image = image
    )
  }

  private fun checkImageCandidates(image: BufferedImage, ids: List<Long>): List<ResultCheck> {
    var it = 0
    val result = ConcurrentLinkedQueue<ResultCheck>()
    while (it < ids.size) {
      val startIndex = it
      val finishIndex = startIndex + COMPARING_COUNT
      val subList = ids.subList(startIndex, min(finishIndex, ids.size))
      val images = subList.mapNotNull { findImageById(it) }

      runBlocking {
        images.forEach { candidate ->
          launch(newThreadContext) {
            val checkResult = ComparingPictures.comparePictures(candidate.image, image)
            if (checkResult != null) {
              result.add(ResultCheck(
                id = candidate.id,
                info = checkImageResponseImageInfo {
                  this.messageId = candidate.messageId
                  this.numberInMessage = candidate.numberInMessage
                  this.additionalInfo = candidate.additionalInfo
                  this.level = checkResult
                }
              ))
            }
          }
        }
      }
      it += COMPARING_COUNT
    }
    return result.toList()
  }

  fun checkImage(request: CheckImageRequest): CheckImageResponse {
    LOGGER.log(Level.FINE, "Got CheckImageRequest")
    val image = readImage(request.image) ?: return checkImageResponse { this.error = "Can't read image" }
    val ids = imageHashConnector.imageCheck(request.group, request.timestamp, image)
    val result = checkImageCandidates(image, ids)
    return checkImageResponse {
      this.imageInfo = CheckImageResponseImagesInfo.newBuilder().addAllImages(result.map { it.info }).build()
    }
  }

  fun deleteImage(request: DeleteImageRequest): DeleteImageResponse {
    LOGGER.log(Level.FINE, "Got DeleteImageRequest: ${request.messageId}-${request.numberInMessage}")

    // Get the image ID
    val imageId = imageConnector.getId(request.messageId, request.numberInMessage)
    if (imageId == null) {
      return deleteImageResponse { this.isDeleted = false }
    }

    // Get the metadata needed for S3 key
    val metadata = imageConnector.getMetadataById(imageId)
    val imageHash = imageHashConnector.getById(imageId)

    var s3DeleteSuccess = false

    // Delete from S3 if we have enough metadata
    if (metadata != null && imageHash != null) {
      val s3Key = s3Storage.generateS3Key(
        group = metadata.group,
        messageId = metadata.messageId,
        numberInMessage = metadata.numberInMessage,
        fileName = metadata.fileName,
        timestamp = imageHash.timestamp
      )

      s3DeleteSuccess = s3Storage.deleteImage(s3Key)
      if (!s3DeleteSuccess) {
        LOGGER.log(Level.WARNING, "Failed to delete image from S3: $s3Key")
      }
    }

    val dbDeletedRows = imageConnector.deleteById(imageId)
    imageHashConnector.deleteById(imageId)
    duplicateInfoConnector.removeById(imageId)

    // If S3 delete failed but database delete worked, consider it successful
    s3DeleteSuccess = s3DeleteSuccess || dbDeletedRows > 0

    return deleteImageResponse {
      this.isDeleted = s3DeleteSuccess
    }
  }

  fun getImageCompressionSize(): GetCompressionSizeResponse {
    return getCompressionSizeResponse {
      if (SIZE_MAX_WIDTH != null) this.x = SIZE_MAX_WIDTH
      if (SIZE_MAX_HEIGHT != null) this.y = SIZE_MAX_HEIGHT
    }
  }
}