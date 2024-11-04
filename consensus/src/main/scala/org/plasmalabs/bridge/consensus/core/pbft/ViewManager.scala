package org.plasmalabs.bridge.consensus.core.pbft

import cats.effect.kernel.{Async, Ref}
import org.plasmalabs.bridge.consensus.pbft.{NewViewRequest, Pm, PrePrepareRequest, ViewChangeRequest}
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.shared.{ReplicaCount, ReplicaId}
import org.plasmalabs.bridge.consensus.core.PBFTInternalGrpcServiceClient

trait ViewManager[F[_]] {

  def currentView: F[Long]

  def currentPrimary: F[Int]

  def resyncAfterViewChange(
    newView:     Long,
    prePrepares: Seq[PrePrepareRequest]
  ): F[Unit]

  def createViewChangeRequest(): F[ViewChangeRequest]

  def logViewChangeRequest(viewChangeRequest: ViewChangeRequest): F[Unit]

}

object ViewManagerImpl {

  import cats.implicits._

  def make[F[_]: Async](
    viewChangeTimeout:   Int,
    storageApi:          StorageApi[F],
    checkpointManager:   CheckpointManager[F],
    requestTimerManager: RequestTimerManager[F]
  )(implicit
    replica:                ReplicaId,
    pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
    replicaCount:           ReplicaCount
  ): F[ViewManager[F]] = {
    for {
      viewChangeRequestMap <- Ref.of[F, Map[Long, Map[Int, ViewChangeRequest]]](
        Map.empty
      )
      currentViewRef <- Ref.of[F, Long](0L)
    } yield new ViewManager[F] {

      override def resyncAfterViewChange(
        newView:     Long,
        prePrepares: Seq[PrePrepareRequest]
      ): F[Unit] =
        for {
          _ <- currentViewRef.set(newView)
          _ <- prePrepares.toList
            .map(prePrepareRequest => pbftProtocolClientGrpc.prePrepare(prePrepareRequest))
            .sequence
        } yield ()

      override def currentPrimary: F[Int] =
        for {
          view <- currentViewRef.get
          primary = (view % replicaCount.value).toInt
        } yield primary

      private def computeNewViewMessage(
        newView: Long
      ): F[(Long, NewViewRequest)] =
        for {
          // get view changes
          viewChangeRequests <- viewChangeRequestMap.get.map(
            _.getOrElse(newView, Map.empty)
          )
          mins = viewChangeRequests.values
            .map(_.lastStableCheckpoinSeqNumber)
            .max
          maxs = viewChangeRequests.values
            .flatMap(
              _.pms.flatMap(_.prepares.map(_.sequenceNumber))
            )
            .max
          newViewMessage = NewViewRequest(
            newViewNumber = newView,
            viewChanges = viewChangeRequests.values.toList,
            preprepares =
              for {
                i <- (mins + 1) to maxs
                thePayload = viewChangeRequests.values
                  .flatMap(_.pms.map(_.prePrepare))
                  .collect {
                    case Some(prePrepare) if prePrepare.sequenceNumber == i =>
                      prePrepare
                  }
                  .headOption
              } yield PrePrepareRequest(
                viewNumber = newView,
                sequenceNumber = i,
                digest = thePayload.map(_.digest).get,
                payload = thePayload.map(_.payload).flatten
              )
          )
          newViewRequest = NewViewRequest(
            newViewNumber = newView,
            viewChanges = viewChangeRequests.values.toList,
            preprepares = newViewMessage.preprepares
          )

        } yield (
          mins,
          newViewRequest
        )

      private def updateToLatestCheckpoint(
        mins:           Long,
        newViewRequest: NewViewRequest
      ): F[Unit] =
        for {
          // insert proof of checkpoint
          _ <- newViewRequest.viewChanges.toList
            .flatMap(_.checkpoints)
            .map { checkpoint =>
              storageApi.insertCheckpointMessage(checkpoint)
            }
            .sequence
          _ <- checkpointManager.setLatestStableCheckpoint(
            StableCheckpoint(
              sequenceNumber = mins,
              certificates = newViewRequest.viewChanges.toList
                .flatMap(_.checkpoints)
                .map(checkpoint => checkpoint.replicaId -> checkpoint)
                .toMap,
              state = Map.empty // FIXME: get the state
            )
          )
          _ <- storageApi.cleanLog(mins)
          _ <- currentViewRef.set(newViewRequest.newViewNumber)
          _ <- requestTimerManager.resetAllTimers()
        } yield ()

      private def performViewChangeForPrimary(newView: Long) =
        for {
          minSAndNewViewRequest <- computeNewViewMessage(newView)
          (mins, newViewRequest) = minSAndNewViewRequest
          _ <- pbftProtocolClientGrpc.newView(newViewRequest)
          _ <- newViewRequest.preprepares.toList
            .map(prePrepareRequest => storageApi.insertPrePrepareMessage(prePrepareRequest))
            .sequence
          shouldWeUpdateCheckpoint <- checkpointManager.latestStableCheckpoint
            .map(
              _.sequenceNumber < mins
            )
          _ <- updateToLatestCheckpoint(mins, newViewRequest).whenA(
            shouldWeUpdateCheckpoint
          )
        } yield ()

      override def logViewChangeRequest(
        viewChangeRequest: ViewChangeRequest
      ): F[Unit] = {
        import scala.concurrent.duration._
        for {
          currentView <- currentViewRef.get
          _ <-
            if (viewChangeRequest.newViewNumber > currentView) {
              // save view change request to storage
              viewChangeRequestMap.update { viewChangeRequestMap =>
                viewChangeRequestMap + (viewChangeRequest.newViewNumber ->
                (viewChangeRequestMap.getOrElse(
                  viewChangeRequest.newViewNumber,
                  Map.empty
                ) + (viewChangeRequest.replicaId -> viewChangeRequest)))
              }
            } else {
              ().pure[F]
            }
          canChangeView <- viewChangeRequestMap.get.map(
            _.get(viewChangeRequest.newViewNumber)
              .map(viewChangeRequestMap => viewChangeRequestMap.size > 2 * replicaCount.maxFailures)
              .getOrElse(false)
          )
          currentPrimaryValue <- currentPrimary
          amIThePrimary = currentPrimaryValue == replica.id
          _ <-
            if (canChangeView && amIThePrimary) {
              performViewChangeForPrimary(viewChangeRequest.newViewNumber)
            } else if (canChangeView && !amIThePrimary) {
              // start timer
              Async[F]
                .background(for {
                  _          <- Async[F].sleep(viewChangeTimeout.seconds)
                  futureView <- currentViewRef.get
                  _ <-
                    if (futureView == currentView) { // we have not changed
                      for {
                        newViewChangeRequest <- createViewChangeRequest()
                        _ <- pbftProtocolClientGrpc.viewChange(
                          newViewChangeRequest.withNewViewNumber(
                            viewChangeRequest.newViewNumber + 1
                          )
                        )
                      } yield ()
                    } else {
                      // the future view has changed
                      Async[F].unit // do nothing
                    }
                } yield ())
                .use(_ => Async[F].unit)
            } else {
              Async[F].unit // do nothing
            }
        } yield ()
      }

      override def currentView: F[Long] = currentViewRef.get

      override def createViewChangeRequest(): F[ViewChangeRequest] =
        for {
          view                   <- currentViewRef.get
          latestStableCheckpoint <- checkpointManager.latestStableCheckpoint
          prePrepareRequests <- storageApi.getPrePrepareMessagesFromSeqNumber(
            view,
            latestStableCheckpoint.sequenceNumber
          )
          prepareRequests <- (prePrepareRequests
            .map { prePrepareRequest =>
              storageApi.getPrepareMessages(
                view,
                prePrepareRequest.sequenceNumber
              )
            })
            .toList
            .sequence
            .map(_.flatten)
          viewChangeRequest = ViewChangeRequest(
            newViewNumber = view + 1,
            lastStableCheckpoinSeqNumber = latestStableCheckpoint.sequenceNumber,
            checkpoints = latestStableCheckpoint.certificates.toList.map(_._2),
            replicaId = replica.id,
            pms = prePrepareRequests
              .filter(prePrepareRequest =>
                prepareRequests
                  .filter(x =>
                    x.sequenceNumber == prePrepareRequest.sequenceNumber &&
                    x.digest.toByteArray
                      .sameElements(prePrepareRequest.digest.toByteArray())
                  )
                  .length >= 2 * replicaCount.maxFailures
              )
              .map(prePrepareRequest =>
                Pm(
                  Some(prePrepareRequest),
                  prepareRequests
                    .filter(x =>
                      x.sequenceNumber == prePrepareRequest.sequenceNumber &&
                      x.digest.toByteArray
                        .sameElements(prePrepareRequest.digest.toByteArray())
                    )
                )
              )
          )
        } yield viewChangeRequest
    }
  }
}
