package org.plasmalabs.bridge.consensus.core.pbft

import cats.effect.kernel.{Async, Outcome, Ref, Resource}
import cats.effect.std.Queue
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.BridgeStateMachineExecutionManager
import org.plasmalabs.bridge.consensus.pbft.{CommitRequest, PrePrepareRequest, PrepareRequest}
import org.plasmalabs.bridge.shared.{ClientId, ReplicaId, StateMachineRequest}
import org.plasmalabs.consensus.core.PBFTInternalGrpcServiceClient

trait RequestStateManager[F[_]] {

  def createStateMachine(requestIdentifier: RequestIdentifier): F[Unit]

  def startProcessingEvents(): Resource[F, F[Outcome[F, Throwable, Unit]]]

}

sealed trait RequestState

case object PrePreparePhase extends RequestState
case class PreparePhase(seqNumber: Long, stateMachineRequest: StateMachineRequest) extends RequestState

case class CommitPhase(
  seqNumber:           Long,
  stateMachineRequest: StateMachineRequest
) extends RequestState

case object Completed extends RequestState

object RequestStateMachineTransitionRelation {

  import cats.implicits._

  private def prepare[F[_]: Async](
    request: PrePrepareRequest
  )(implicit
    replica:                ReplicaId,
    pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ) =
    pbftProtocolClientGrpc
      .prepare(
        PrepareRequest(
          viewNumber = request.viewNumber,
          sequenceNumber = request.sequenceNumber,
          digest = request.digest,
          replicaId = replica.id
        )
      )
      .void

  private def commit[F[_]: Async](
    request: PrepareRequest
  )(implicit
    replica:                ReplicaId,
    pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ) =
    pbftProtocolClientGrpc
      .commit(
        CommitRequest(
          viewNumber = request.viewNumber,
          sequenceNumber = request.sequenceNumber,
          digest = request.digest,
          replicaId = replica.id
        )
      )
      .void

  private def complete[F[_]: Async: Logger](
    seqNumber: Long,
    smRequest: StateMachineRequest,
    rmOp:      F[Unit]
  )(implicit
    requestTimerManager:                RequestTimerManager[F],
    bridgeStateMachineExecutionManager: BridgeStateMachineExecutionManager[F]
  ) =
    for {
      _ <- bridgeStateMachineExecutionManager.executeRequest(seqNumber, smRequest)
      reqIdentifier = RequestIdentifier(
        ClientId(smRequest.clientNumber),
        smRequest.timestamp
      )
      _ <- debug"Request $reqIdentifier completed, clearing timer"
      _ <- requestTimerManager.clearTimer(reqIdentifier)
      _ <- rmOp
    } yield ()

  private def viewChange[F[_]: Async]()(implicit
    viewManager:            ViewManager[F],
    pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ) =
    for {
      request <- viewManager.createViewChangeRequest()
      _       <- pbftProtocolClientGrpc.viewChange(request)
    } yield ()

  def transition[F[_]: Async: Logger](
    rmOp: F[Unit]
  )(requestState: RequestState, event: PBFTInternalEvent)(implicit
    replica:                            ReplicaId,
    viewManager:                        ViewManager[F],
    requestTimerManager:                RequestTimerManager[F],
    pbftProtocolClientGrpc:             PBFTInternalGrpcServiceClient[F],
    bridgeStateMachineExecutionManager: BridgeStateMachineExecutionManager[F]
  ): (Option[RequestState], F[Unit]) =
    (requestState, event) match {
      case (PrePreparePhase, PrePreparedInserted(request)) =>
        (Some(PreparePhase(request.sequenceNumber, request.payload.get)), prepare[F](request))
      case (PreparePhase(seqNumber, smRequest), Prepared(_, request)) =>
        (Some(CommitPhase(seqNumber, smRequest)), commit[F](request))
      case (CommitPhase(seqNumber, smRequest), Commited(_, _)) =>
        (Some(Completed), complete(seqNumber, smRequest, rmOp))
      case (_, PBFTTimeoutEvent(_)) =>
        // if there is a timeout event, then we remove only
        // the request from the state machine
        // we do not touch other request because there might be other
        // messages that are still in the pipeline
        // however, the pipeline will stop processing the messages so
        // eventually there will not be any more changes.
        (
          Some(Completed),
          viewChange()
        )
      case (_, _) =>
        (None, Async[F].unit)
    }
}

object RequestStateManagerImpl {

  import cats.implicits._

  def make[F[_]: Async: Logger](
    viewManager:         ViewManager[F],
    queue:               Queue[F, PBFTInternalEvent],
    requestTimerManager: RequestTimerManager[F],
    bridgeStateMachineExecutionManager: BridgeStateMachineExecutionManager[
      F
    ]
  )(implicit
    replica:                ReplicaId,
    pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
  ): F[RequestStateManager[F]] = {

    implicit val iViewManager = viewManager

    implicit val iBridgeStateMachineExecutionManager =
      bridgeStateMachineExecutionManager
    implicit val iRequestTimerManager = requestTimerManager
    for {
      state <- Ref.of[F, Map[RequestIdentifier, RequestState]](Map.empty)
    } yield new RequestStateManager[F] {

      override def startProcessingEvents(): Resource[F, F[Outcome[F, Throwable, Unit]]] =
        Async[F].background(
          fs2.Stream
            .fromQueueUnterminated(queue)
            .evalMap(event => processEvent(event))
            .compile
            .drain
        )

      override def createStateMachine(
        requestIdentifier: RequestIdentifier
      ): F[Unit] =
        state.update(
          _ + (requestIdentifier -> PrePreparePhase)
        )

      private def processEvent(
        event: PBFTInternalEvent
      ): F[Unit] = for {
        _ <- state.flatModify { map =>
          val identifier = event.requestIdentifier
          val someCurrentState = map.get(identifier)
          someCurrentState match {
            case None =>
              (map, Async[F].unit)
            case Some(currentState) =>
              val (newState, action) =
                RequestStateMachineTransitionRelation
                  .transition[F](
                    state.update(_ - identifier)
                  )(
                    currentState,
                    event
                  )
              (
                map.updated(
                  identifier,
                  newState.getOrElse(currentState)
                ),
                action
              )
          }
        }

      } yield ()
    }
  }
}
