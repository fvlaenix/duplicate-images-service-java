package com.fvlaenix.duplicate.image

import com.fvlaenix.duplicate.database.Connector
import com.fvlaenix.duplicate.database.DuplicateInfoConnector
import com.fvlaenix.duplicate.database.ImageConnector
import com.fvlaenix.duplicate.protobuf.*
import com.fvlaenix.duplicate.protobuf.CheckImageResponseImagesInfoKt.checkImageResponseImageInfo
import com.fvlaenix.image.protobuf.Image
import com.luciad.imageio.webp.WebPReadParam
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
import kotlin.io.path.extension
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

private val PROPERTIES = ENV_PROPERTIES?.apply { LOGGER.info("Using env properties") } ?: 
  RESERVE_PROPERTIES?.apply { LOGGER.info("Using git properties") } ?: 
  throw IllegalStateException("Can't find env path ${System.getenv("PATH_TO_COMPARING_PROPERTIES")} and local path")

private val WIDTH = PROPERTIES.getProperty("width")?.toInt() ?: throw IllegalStateException("width should exists in properties")
private val HEIGHT = PROPERTIES.getProperty("height")?.toInt() ?: throw IllegalStateException("height should exists in properties")

class ComparingMachine(database: Database) {
  
  private val imageConnector = ImageConnector(database)
  private val duplicateInfoConnector = DuplicateInfoConnector(database)
  
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
    val extension = Path(fileName).extension
    val bufferedImage = try {
      if (extension == "webp") {
        val reader = ImageIO.getImageReadersByMIMEType("image/webp").next()
        val readParam = WebPReadParam()
        readParam.isBypassFiltering = true
        byteString.toByteArray().inputStream().use { inputStream ->
          ImageIO.createImageInputStream(inputStream).use { imageInputStream ->
            reader.input = imageInputStream
            reader.read(0, readParam)
          }
        }
      } else {
        byteString.toByteArray().inputStream().use { stream ->
          ImageIO.read(stream)
        }
      }
    } catch (e: IIOException) {
      LOGGER.log(Level.SEVERE, "Exception while trying read image with name $fileName", e)
      return null
    }
    val maxWidth = SIZE_MAX_WIDTH
    val maxHeight = SIZE_MAX_HEIGHT
    if (maxWidth != null && bufferedImage.width != maxWidth) {
      LOGGER.log(Level.WARNING, "Can't read image with incompatible size")
      return Thumbnails.of(bufferedImage).width(maxWidth).asBufferedImage()
    }
    if (maxHeight != null && bufferedImage.height != maxHeight) {
      LOGGER.log(Level.WARNING, "Can't read image with incompatible size")
      return Thumbnails.of(bufferedImage).height(maxHeight).asBufferedImage()
    }
    return bufferedImage
  }
  
  fun addImageWithCheck(request: AddImageRequest): AddImageResponse {
    LOGGER.log(Level.FINE, "Got addImageRequest: ${request.imageId}")
    val image = readImage(request.image) ?: return addImageResponse { this.error = "Can't read image" }
    val added = imageConnector.addImageWithCheck(
      request.group,
      request.imageId,
      request.additionalInfo,
      image,
      request.image.fileName,
      request.timestamp
    )
    val checkResult = checkImageCandidates(image, added.similarIds)
    checkResult.forEach { original ->
      duplicateInfoConnector.add(
        request.group,
        original.imageId,
        request.imageId,
        original.level
      )
    }
    return addImageResponse {
      this.responseOk = addImageResponseOk { 
        this.isAdded = added.isAdded
        this.imageInfo = CheckImageResponseImagesInfo.newBuilder().addAllImages(checkResult).build()
      }
    }
  }

  fun existsImage(request: ExistsImageRequest): ExistsImageResponse {
    LOGGER.log(Level.FINE, "Got ExistsImageRequest: ${request.imageInfo}")
    return existsImageResponse { this.isExists = imageConnector.isImageExistsById(request.imageInfo) }
  }

  private fun checkImageCandidates(image: BufferedImage, ids: List<String>): List<CheckImageResponseImagesInfo.CheckImageResponseImageInfo> {
    var it = 0
    val result = ConcurrentLinkedQueue<CheckImageResponseImagesInfo.CheckImageResponseImageInfo>()
    while (it < ids.size) {
      val startIndex = it
      val finishIndex = startIndex + COMPARING_COUNT
      val subList = ids.subList(startIndex, min(finishIndex, ids.size))
      val images = subList.associateWith { imageConnector.getImageById(it) }
      runBlocking {
        images.forEach { (id, candidate) ->
          if (candidate == null) return@forEach
          launch(newThreadContext) {
            val checkResult = ComparingPictures.comparePictures(candidate.image, image)
            if (checkResult != null) {
              result.add(checkImageResponseImageInfo { 
                this.imageId = id
                this.additionalInfo = candidate.additionalInfo
                this.level = checkResult 
              })
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
    val ids = imageConnector.getSimilarImages(image, request.group, request.timestamp)
    val result = checkImageCandidates(image, ids)
    return checkImageResponse { this.imageInfo = CheckImageResponseImagesInfo.newBuilder().addAllImages(result).build() }
  }

  fun deleteImage(request: DeleteImageRequest): DeleteImageResponse {
    LOGGER.log(Level.FINE, "Got DeleteImageRequest: ${request.imageId}")
    val response = deleteImageResponse { this.isDeleted = imageConnector.deleteById(request.imageId) }
    duplicateInfoConnector.removeById(request.imageId)
    return response
  }
  
  fun getImageCompressionSize(): GetCompressionSizeResponse {
    return getCompressionSizeResponse {
      "$SIZE_MAX_WIDTH, $SIZE_MAX_HEIGHT"
      if (SIZE_MAX_WIDTH != null) this.x = SIZE_MAX_WIDTH
      if (SIZE_MAX_HEIGHT != null) this.y = SIZE_MAX_HEIGHT
    }
  }
}