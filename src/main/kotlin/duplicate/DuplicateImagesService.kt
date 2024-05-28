package com.fvlaenix.duplicate

import com.fvlaenix.alive.protobuf.IsAliveRequest
import com.fvlaenix.alive.protobuf.IsAliveResponse
import com.fvlaenix.alive.protobuf.isAliveResponse
import com.fvlaenix.duplicate.image.ComparingMachine
import com.fvlaenix.duplicate.protobuf.*
import org.jetbrains.exposed.sql.Database
import java.util.logging.Level
import java.util.logging.Logger

private val LOG = Logger.getLogger(DuplicateImagesService::class.java.name)

class DuplicateImagesService(database: Database) : DuplicateImagesServiceGrpcKt.DuplicateImagesServiceCoroutineImplBase() {

  private val comparingMachine = ComparingMachine(database)

  override suspend fun addImageWithCheck(request: AddImageRequest): AddImageResponse =
    runCatching { comparingMachine.addImageWithCheck(request) }.getOrElse { throwable ->
      LOG.log(Level.SEVERE, "Exception on add image with check", throwable)
      addImageResponse { this.error = "Exception on add image with check: ${throwable.localizedMessage}" }
    }
    

  override suspend fun existsImage(request: ExistsImageRequest): ExistsImageResponse =
    runCatching { comparingMachine.existsImage(request) }.getOrElse { throwable ->
      LOG.log(Level.SEVERE, "Exception on exists image", throwable)
      existsImageResponse { this.error = "Exception on exists image: ${throwable.localizedMessage}" }
    }

  override suspend fun checkImage(request: CheckImageRequest): CheckImageResponse =
    runCatching { comparingMachine.checkImage(request) }.getOrElse { throwable ->
      LOG.log(Level.SEVERE, "Exception on check image", throwable)
      checkImageResponse { this.error = "Exception on check image: ${throwable.localizedMessage}" }
    }

  override suspend fun deleteImage(request: DeleteImageRequest): DeleteImageResponse =
    runCatching { comparingMachine.deleteImage(request) }.getOrElse { throwable ->
      LOG.log(Level.SEVERE, "Exception on delete image", throwable)
      deleteImageResponse { this.error = "Exception on delete image: ${throwable.localizedMessage}" }
    }

  override suspend fun getImageCompressionSize(request: GetCompressionSizeRequest): GetCompressionSizeResponse =
    runCatching { comparingMachine.getImageCompressionSize() }.getOrElse { throwable ->
      LOG.log(Level.SEVERE, "Exception on get compression size", throwable)
      getCompressionSizeResponse { this.error = "Exception on get compression size: ${throwable.localizedMessage}" }
    }

  override suspend fun isAlive(request: IsAliveRequest): IsAliveResponse {
    return isAliveResponse {  }
  }
}