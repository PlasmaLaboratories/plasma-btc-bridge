package org.plasmalabs.bridge.consensus.core.pbft

import org.plasmalabs.bridge.consensus.core.pbft.statemachine.PBFTState
import org.plasmalabs.bridge.consensus.pbft.CheckpointRequest

private[pbft] case class StableCheckpoint(
  sequenceNumber: Long,
  certificates:   Map[Int, CheckpointRequest],
  state:          Map[String, PBFTState]
)

private[pbft] case class UnstableCheckpoint(
  certificates: Map[Int, CheckpointRequest]
)

private[pbft] case class StateSnapshot(
  sequenceNumber: Long,
  digest:         String,
  state:          Map[String, PBFTState]
)
