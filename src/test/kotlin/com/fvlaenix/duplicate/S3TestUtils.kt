package com.fvlaenix.duplicate

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.CreateBucketRequest
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import java.util.*
import java.util.logging.Logger

/**
 * Utility class for S3 testing with LocalStack
 */
object S3TestUtils {
  private val logger = Logger.getLogger(S3TestUtils::class.java.name)

  // Singleton container instance
  private var localStackContainer: LocalStackContainer? = null

  // Test S3 client
  private var testS3Client: AmazonS3? = null

  // Test bucket name
  private const val TEST_BUCKET_NAME = "test-image-bucket"

  /**
   * Start LocalStack container and initialize S3 client
   */
  fun startLocalStack(): LocalStackContainer {
    if (localStackContainer == null) {
      logger.info("Starting LocalStack container...")

      localStackContainer = LocalStackContainer(
        DockerImageName.parse("localstack/localstack:latest")
      ).withServices(LocalStackContainer.Service.S3)

      localStackContainer!!.start()

      // Create S3 client
      testS3Client = AmazonS3ClientBuilder.standard()
        .withEndpointConfiguration(
          AwsClientBuilder.EndpointConfiguration(
            localStackContainer!!.getEndpointOverride(LocalStackContainer.Service.S3).toString(),
            localStackContainer!!.region
          )
        )
        .withCredentials(
          AWSStaticCredentialsProvider(
            BasicAWSCredentials(
              localStackContainer!!.accessKey,
              localStackContainer!!.secretKey
            )
          )
        )
        .withPathStyleAccessEnabled(true)
        .build()

      // Create test bucket
      testS3Client!!.createBucket(CreateBucketRequest(TEST_BUCKET_NAME))

      logger.info("LocalStack started with S3 endpoint: ${localStackContainer!!.getEndpointOverride(LocalStackContainer.Service.S3)}")
      logger.info("Test bucket '$TEST_BUCKET_NAME' created")
    }

    return localStackContainer!!
  }

  /**
   * Creates a test S3 properties object for LocalStack
   */
  fun createTestS3Properties(): Properties {
    if (localStackContainer == null) {
      startLocalStack()
    }

    return Properties().apply {
      setProperty("accessKey", localStackContainer!!.accessKey)
      setProperty("secretKey", localStackContainer!!.secretKey)
      setProperty("region", localStackContainer!!.region)
      setProperty("bucketName", TEST_BUCKET_NAME)
      setProperty("endpoint", localStackContainer!!.getEndpointOverride(LocalStackContainer.Service.S3).toString())
      setProperty("pathStyleAccess", "true")  // Important for LocalStack
    }
  }

  /**
   * Get a test S3 client configured for LocalStack
   */
  fun getTestS3Client(): AmazonS3 {
    if (testS3Client == null) {
      startLocalStack()
    }
    return testS3Client!!
  }

  /**
   * Stop the LocalStack container
   */
  fun stopLocalStack() {
    localStackContainer?.stop()
    localStackContainer = null
    testS3Client = null
    logger.info("LocalStack container stopped")
  }
}