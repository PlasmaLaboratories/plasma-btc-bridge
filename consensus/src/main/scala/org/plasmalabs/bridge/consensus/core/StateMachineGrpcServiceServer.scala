package org.plasmalabs.bridge.consensus.core

import cats.effect.IO
import cats.effect.kernel.Sync
import com.google.protobuf.ByteString
import io.grpc.Metadata
import org.plasmalabs.bridge.consensus.core.pbft.{RequestIdentifier, RequestTimerManager, ViewManager}
import org.plasmalabs.bridge.consensus.core.{LastReplyMap, PBFTInternalGrpcServiceClient, PublicApiClientGrpcMap}
import org.plasmalabs.bridge.consensus.pbft.PrePrepareRequest
import org.plasmalabs.bridge.consensus.service.MintingStatusReply.{Result => MSReply}
import org.plasmalabs.bridge.consensus.service.{
  MintingStatusReply,
  MintingStatusRes,
  SessionNotFoundRes,
  StateMachineServiceFs2Grpc
}
import org.plasmalabs.bridge.consensus.shared.PeginSessionInfo
import org.plasmalabs.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import org.plasmalabs.bridge.shared.StateMachineRequest.Operation.StartSession
import org.plasmalabs.bridge.shared.{
  BridgeCryptoUtils,
  ClientId,
  Empty,
  MintingStatusOperation,
  ReplicaCount,
  ReplicaId,
  StateMachineRequest
}
import org.typelevel.log4cats.Logger

import java.security.{KeyPair => JKeyPair, MessageDigest}
import java.util.UUID

object StateMachineGrpcServiceServer {

  def stateMachineGrpcServiceServer(
    keyPair:                JKeyPair,
    pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[IO],
    idReplicaClientMap:     Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
    seqNumberManager:       SequenceNumberManager[IO]
  )(implicit
    lastReplyMap:           LastReplyMap,
    requestTimerManager:    RequestTimerManager[IO],
    sessionManager:         SessionManagerAlgebra[IO],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
    viewManager:            ViewManager[IO],
    replicaId:              ReplicaId,
    replicaCount:           ReplicaCount,
    logger:                 Logger[IO]
  ) = StateMachineServiceFs2Grpc.bindServiceResource(
    serviceImpl = new StateMachineServiceFs2Grpc[IO, Metadata] {

      // log4cats syntax
      import org.typelevel.log4cats.syntax._
      import cats.implicits._

      private def mintingStatusAux[F[_]: Sync: Logger](
        value: MintingStatusOperation
      )(implicit
        sessionManager: SessionManagerAlgebra[F]
      ) =
        for {
          session <- sessionManager.getSession(value.sessionId)
          somePegin <- session match {
            case Some(p: PeginSessionInfo) =>
              val l = implicitly[Logger[F]]
              debug"minting status session: ${p.mintingBTCState.toString()}" (l) >>
              Sync[F].delay(Option(p))
            case None => Sync[F].delay(None)
            case _ =>
              Sync[F].raiseError(new Exception("Invalid session type"))
          }
          resp: MSReply = somePegin match {
            case Some(pegin) =>
              MSReply.MintingStatus(
                MintingStatusRes(
                  sessionId = value.sessionId,
                  mintingStatus = pegin.mintingBTCState.toString(),
                  address = pegin.redeemAddress,
                  redeemScript =
                    s""""threshold(1, sha256(${pegin.sha256}) and height(${pegin.minHeight}, ${pegin.maxHeight}))"""
                )
              )
            case None =>
              MSReply.SessionNotFound(
                SessionNotFoundRes(
                  value.sessionId
                )
              )
          }
        } yield resp

      override def mintingStatus(
        request: MintingStatusOperation,
        ctx:     Metadata
      ): IO[MintingStatusReply] =
        mintingStatusAux[IO](request).map(MintingStatusReply(_))

      def executeRequest(
        request: org.plasmalabs.bridge.shared.StateMachineRequest,
        ctx:     Metadata
      ): IO[Empty] =
        Option(
          lastReplyMap.underlying.get(
            (ClientId(request.clientNumber), request.timestamp)
          )
        ) match {
          case Some(result) => // we had a cached response
            for {
              viewNumber <- viewManager.currentView
              _          <- debug"Request.clientNumber: ${request.clientNumber}"
              _ <- publicApiClientGrpcMap
                .underlying(ClientId(request.clientNumber))
                ._1
                .replyStartPegin(request.timestamp, viewNumber, result)
            } yield Empty()
          case None => // w are going to execute the request
            for {
              currentView <- viewManager.currentView
              currentPrimary = (currentView % replicaCount.value).toInt
              reqIdentifier = RequestIdentifier(
                ClientId(request.clientNumber),
                request.timestamp
              )
              _ <-
                if (currentPrimary != replicaId.id)
                  // we are not the primary, forward the request
                  requestTimerManager.startTimer(
                    reqIdentifier
                  ) >>
                  idReplicaClientMap(
                    currentPrimary
                  ).executeRequest(request, ctx)
                else {
                  import org.plasmalabs.bridge.shared.implicits._
                  val prePrepareRequestNoSeq = PrePrepareRequest(
                    viewNumber = currentView,
                    digest = ByteString.copyFrom(
                      MessageDigest
                        .getInstance("SHA-256")
                        .digest(request.signableBytes)
                    ),
                    payload = Some(request match {
                      case StateMachineRequest(_, _, _, operation: StartSession, _) =>
                        request.withOperation(
                          operation.copy(
                            value = operation.value.withSessionId(
                              UUID.randomUUID().toString
                            )
                          )
                        )
                      case _ => request

                    })
                  )
                  (for {
                    currentSequence <- seqNumberManager.getAndIncrease
                    prePrepareRequest = prePrepareRequestNoSeq.withSequenceNumber(
                      currentSequence
                    )
                    signedPrePreparedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      prePrepareRequest.signableBytes
                    )
                    signedprePrepareRequest = prePrepareRequest.withSignature(
                      ByteString.copyFrom(signedPrePreparedBytes)
                    )
                    _ <- pbftProtocolClientGrpc.prePrepare(
                      signedprePrepareRequest
                    )
                  } yield ()) >> IO.pure(Empty())

                }
            } yield Empty()

        }
    }
  )

}
