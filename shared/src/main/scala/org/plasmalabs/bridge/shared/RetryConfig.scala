package org.plasmalabs.bridge.shared

import scala.concurrent.duration._

trait StateMachineServiceGrpcClientRetryConfig {
  def primaryResponseWait: FiniteDuration
  def otherReplicasResponseWait: FiniteDuration
  def retryPolicy: RetryPolicy
}

case class StateMachineServiceGrpcClientRetryConfigImpl(
  primaryResponseWait: FiniteDuration,
  otherReplicasResponseWait: FiniteDuration,
  retryPolicy: RetryPolicy
) extends StateMachineServiceGrpcClientRetryConfig

case class RetryPolicy(
  initialDelay: FiniteDuration,
  maxRetries: Int,
  delayMultiplier: Int
)

object StateMachineServiceGrpcClientRetryConfig {
  def apply(
    primaryResponseWait: Int,
    otherReplicasResponseWait: Int,
    initialDelay : Int,
    maxRetries: Int, 
    delayMultiplier: Int
  ): StateMachineServiceGrpcClientRetryConfig = {
    StateMachineServiceGrpcClientRetryConfigImpl(
      primaryResponseWait = primaryResponseWait.seconds,
      otherReplicasResponseWait = otherReplicasResponseWait.seconds,
      retryPolicy = RetryPolicy(
        initialDelay = initialDelay.seconds,
        maxRetries = maxRetries, 
        delayMultiplier = delayMultiplier
      )
    )
  }
}

trait PBFTInternalGrpcServiceClientRetryConfig {
  def retryPolicy: RetryPolicy
}

case class PBFTInternalGrpcServiceClientRetryConfigImpl(
  retryPolicy: RetryPolicy
) extends PBFTInternalGrpcServiceClientRetryConfig

object PBFTInternalGrpcServiceClientRetryConfig {
  def apply(
    initialDelay: Int,
    maxRetries: Int, 
    delayMultiplier: Int
  ): PBFTInternalGrpcServiceClientRetryConfig = {
    PBFTInternalGrpcServiceClientRetryConfigImpl(
      retryPolicy = RetryPolicy(
        initialDelay = initialDelay.seconds,
        maxRetries = maxRetries, 
        delayMultiplier = delayMultiplier
      )
    )
  }
}