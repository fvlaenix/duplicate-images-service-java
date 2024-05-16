package com.fvlaenix.duplicate

import com.fvlaenix.alive.protobuf.IsAliveRequest
import com.fvlaenix.alive.protobuf.IsAliveResponse
import com.fvlaenix.alive.protobuf.isAliveResponse
import com.fvlaenix.duplicate.image.ComparingMachine
import com.fvlaenix.duplicate.protobuf.*
import org.jetbrains.exposed.sql.Database

class DuplicateImagesService(database: Database) : DuplicateImagesServiceGrpcKt.DuplicateImagesServiceCoroutineImplBase() {

  private val comparingMachine = ComparingMachine(database)

  override suspend fun addImageWithCheck(request: AddImageRequest): AddImageResponse =
    comparingMachine.addImageWithCheck(request)

  override suspend fun existsImage(request: ExistsImageRequest): ExistsImageResponse =
    comparingMachine.existsImage(request)

  override suspend fun checkImage(request: CheckImageRequest): CheckImageResponse =
    comparingMachine.checkImage(request)

  override suspend fun deleteImage(request: DeleteImageRequest): DeleteImageResponse =
    comparingMachine.deleteImage(request)

  override suspend fun getImageCompressionSize(request: GetCompressionSizeRequest): GetCompressionSizeResponse =
    comparingMachine.getImageCompressionSize()

  override suspend fun isAlive(request: IsAliveRequest): IsAliveResponse {
    return isAliveResponse {  }
  }
}