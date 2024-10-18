package org.plasmalabs.bridge.shared

import cats.effect.kernel.{Async, Ref, Sync}
import cats.implicits._
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import org.plasmalabs.bridge.consensus.service.StateMachineReply.Result.{SessionNotFound, StartSession}
import org.plasmalabs.bridge.consensus.service.{ResponseServiceFs2Grpc, StateMachineReply}
import org.plasmalabs.bridge.shared.{
  BridgeCryptoUtils,
  BridgeError,
  BridgeResponse,
  ConsensusClientMessageId,
  Empty,
  InvalidInput,
  SessionNotFoundError,
  StartPeginSessionResponse
}

import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

object ResponseGrpcServiceServer {

  def responseGrpcServiceServer[F[_]: Async: Logger](
    currentViewRef: Ref[F, Long],
    replicaKeysMap: Map[Int, PublicKey],
    messageVotersMap: ConcurrentHashMap[
      ConsensusClientMessageId,
      ConcurrentHashMap[Int, Int]
    ],
    messageResponseMap: ConcurrentHashMap[
      ConsensusClientMessageId,
      ConcurrentHashMap[Either[
        BridgeError,
        BridgeResponse
      ], LongAdder]
    ]
  ) =
    ResponseServiceFs2Grpc.bindServiceResource(
      serviceImpl = new ResponseServiceFs2Grpc[F, Metadata] {

        def deliverResponse(
          request: StateMachineReply,
          ctx:     Metadata
        ): F[Empty] = {

          import org.plasmalabs.bridge.shared.implicits._
          for {
            _ <- trace"Received response from replica ${request.replicaNumber}"
            publicKey = replicaKeysMap(request.replicaNumber)
            isValidSignature <- BridgeCryptoUtils.verifyBytes(
              publicKey,
              request.signableBytes,
              request.signature.toByteArray
            )
            _ <- trace"Signature is valid"
            _ <-
              if (isValidSignature) {
                val response: Either[BridgeError, BridgeResponse] =
                  request.result match {
                    case StateMachineReply.Result.Empty =>
                      Right(PBFTInternalResponse)
                    case StateMachineReply.Result.InvalidInput(value) =>
                      Left(
                        InvalidInput(
                          value.errorMessage
                        )
                      )
                    case SessionNotFound(value) =>
                      Left(SessionNotFoundError(value.sessionId))
                    case StartSession(value) =>
                      Right(
                        StartPeginSessionResponse(
                          sessionID = value.sessionId,
                          script = value.script,
                          escrowAddress = value.escrowAddress,
                          descriptor = value.descriptor,
                          minHeight = value.minHeight,
                          maxHeight = value.maxHeight
                        )
                      )
                  }
                for {
                  _ <- currentViewRef.update(x => if (x < request.viewNumber) request.viewNumber else x)
                  votersMap <- Sync[F].delay(
                    messageVotersMap
                      .get(
                        ConsensusClientMessageId(request.timestamp)
                      ): ConcurrentHashMap[Int, Int]
                  )
                  voteMap <- Sync[F].delay(
                    messageResponseMap
                      .get(
                        ConsensusClientMessageId(request.timestamp)
                      ): ConcurrentHashMap[Either[
                      BridgeError,
                      BridgeResponse
                    ], LongAdder]
                  )
                  _ <- Option(voteMap)
                    .zip(Option(votersMap))
                    .fold(
                      info"Vote map or voter map not found, vote completed"
                    ) { case (voteMap, votersMap) =>
                      // we check if the replica already voted
                      Option(votersMap.get(request.replicaNumber)).fold(
                        // no vote from this replica yet
                        Sync[F].delay(
                          votersMap
                            .computeIfAbsent(request.replicaNumber, _ => 1)
                        ) >> Sync[F].delay(
                          voteMap
                            .computeIfAbsent(response, _ => new LongAdder())
                            .increment()
                        )
                      ) { _ =>
                        warn"Duplicate vote from replica ${request.replicaNumber}"
                      }
                    }
                } yield ()
              } else {
                error"Invalid signature in response"
              }
          } yield Empty()
        }
      }
    )
}
