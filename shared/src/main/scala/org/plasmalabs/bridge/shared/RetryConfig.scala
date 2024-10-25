package org.plasmalabs.bridge.shared

import scala.concurrent.duration._

case class RetryPolicy(
  initialDelay: FiniteDuration,
  maxRetries: Int,
  delayMultiplier: Int
)

case class StateMachineServiceGrpcClientRetryConfig(
  primaryResponseWait: FiniteDuration,
  otherReplicasResponseWait: FiniteDuration,
  retryPolicy: RetryPolicy
)