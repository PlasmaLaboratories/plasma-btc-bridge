package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Ref, Sync}
import org.typelevel.log4cats.Logger
import org.plasmalabs.bridge.shared.{ReplicaCount, StateMachineRequest}

private[statemachine] trait ExecutionElegibilityManager[F[_]] {

  def appendOrUpdateRequest(sequenceNumber: Long, request: StateMachineRequest): F[Unit]

  def getNextExecutableRequest(): F[Option[(Long, StateMachineRequest)]]

}

private[statemachine] object ExecutionElegibilityManagerImpl {

  import cats.implicits._

  def make[F[_]: Sync: Logger]()(implicit
    replicaCount: ReplicaCount
  ) = for {
    lastExecutedSequenceNumberRef <- Ref.of[F, Long](0)
    toExecute                     <- Ref.of[F, Map[Long, StateMachineRequest]](Map.empty)
    voteMapRef                    <- Ref.of[F, Map[StateMachineRequest.Operation, Int]](Map.empty)
  } yield new ExecutionElegibilityManager[F] {
    import org.typelevel.log4cats.syntax._

    def appendOrUpdateRequest(sequenceNumber: Long, request: StateMachineRequest): F[Unit] = for {
      _ <- toExecute.update(_.updated(sequenceNumber, request))
      _ <- voteMapRef.update(_.updatedWith(request.operation) {
        case Some(count) => Some(count + 1)
        case None        => Some(1)
      })
    } yield ()

    def getNextExecutableRequest(): F[Option[(Long, StateMachineRequest)]] =
      (for {
        lastExecutedSequenceNumber <- lastExecutedSequenceNumberRef.get
        requestsToExecute          <- toExecute.get.map(_.get(lastExecutedSequenceNumber + 1))
      } yield {
        val sequenceNumber = lastExecutedSequenceNumber + 1
        requestsToExecute match {
          case Some(request) if (request.operation.startSession.isDefined) =>
            lastExecutedSequenceNumberRef.update(_ => sequenceNumber) >>
            toExecute.update(_ - sequenceNumber) >>
            Option((sequenceNumber, request)).pure[F]
          case Some(request) =>
            for {
              voteMap <- voteMapRef.get
              canExecute = voteMap.get(request.operation) match {
                case Some(count) =>
                  count >= (replicaCount.value - replicaCount.maxFailures)
                case None => false
              }
              _ <- trace"Can execute: $canExecute"
              _ <- lastExecutedSequenceNumberRef.update(_ => sequenceNumber)
              _ <- toExecute.update(_ - sequenceNumber)
              _ <-
                if (canExecute)
                  voteMapRef.update(_ - request.operation)
                else Sync[F].unit
            } yield
              if (canExecute)
                Option((sequenceNumber, request))
              else
                Option(
                  (
                    sequenceNumber,
                    request.withOperation(org.plasmalabs.bridge.shared.StateMachineRequest.Operation.Empty)
                  )
                )
          case _ =>
            none[(Long, StateMachineRequest)].pure[F]
        }
      }).flatten
  }

}
