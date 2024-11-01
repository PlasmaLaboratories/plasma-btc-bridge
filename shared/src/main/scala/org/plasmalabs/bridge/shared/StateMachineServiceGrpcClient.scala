package org.plasmalabs.bridge.shared

import cats.effect.kernel.{Async, Ref, Sync}
import cats.effect.std.Mutex
import com.google.protobuf.ByteString
import fs2.grpc.syntax.all._
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import org.plasmalabs.bridge.consensus.service.{MintingStatusReply, StateMachineServiceFs2Grpc}
import org.plasmalabs.bridge.shared.{
  BridgeCryptoUtils,
  BridgeError,
  BridgeResponse,
  MintingStatusOperation,
  PostClaimTxOperation,
  PostDepositBTCOperation,
  PostRedemptionTxOperation,
  ReplicaCount,
  ReplicaNode,
  StartSessionOperation,
  StateMachineRequest,
  TimeoutDepositBTCOperation,
  TimeoutError,
  TimeoutTBTCMintOperation,
  StateMachineServiceGrpcClientRetryConfig
}

import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import cats.Parallel

trait StateMachineServiceGrpcClient[F[_]] {

  def startPegin(
    startSessionOperation: StartSessionOperation
  )(implicit
    clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def postDepositBTC(
    postDepositBTCOperation: PostDepositBTCOperation
  )(implicit
    clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def timeoutDepositBTC(
    timeoutDepositBTCOperation: TimeoutDepositBTCOperation
  )(implicit
    clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def timeoutTBTCMint(
    timeoutTBTCMintOperation: TimeoutTBTCMintOperation
  )(implicit
    clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def postRedemptionTx(
    postRedemptionTxOperation: PostRedemptionTxOperation
  )(implicit
    clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def postClaimTx(
    postClaimTxOperation: PostClaimTxOperation
  )(implicit
    clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def mintingStatus(
    mintingStatusOperation: MintingStatusOperation
  )(implicit
    clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]
}

object StateMachineServiceGrpcClientImpl {

  import cats.implicits._
  import org.plasmalabs.bridge.shared.implicits._
  import scala.concurrent.duration._


  def makeContainer[F[_]: Parallel: Async: Logger](
    currentViewRef: Ref[F, Long],
    keyPair:        KeyPair,
    mutex:          Mutex[F],
    replicaNodes:   List[ReplicaNode[F]],
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
    ],
  )(implicit replicaCount: ReplicaCount, stateMachineConf: StateMachineServiceGrpcClientRetryConfig) = {
    for {
      idClientList <- (for {
        replicaNode <- replicaNodes
      } yield for {
        channel <-
          (if (replicaNode.backendSecure)
             ManagedChannelBuilder
               .forAddress(replicaNode.backendHost, replicaNode.backendPort)
               .useTransportSecurity()
           else
             ManagedChannelBuilder
               .forAddress(replicaNode.backendHost, replicaNode.backendPort)
               .usePlaintext()).resource[F]
        consensusClient <- StateMachineServiceFs2Grpc.stubResource(
          channel
        )
      } yield (replicaNode.id -> consensusClient)).sequence
      replicaMap = idClientList.toMap
    } yield new StateMachineServiceGrpcClient[F] {

      def postClaimTx(
        postClaimTxOperation: PostClaimTxOperation
      )(implicit
        clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.PostClaimTx(
              postClaimTxOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      def postRedemptionTx(
        postRedemptionTxOperation: PostRedemptionTxOperation
      )(implicit
        clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.PostRedemptionTx(
              postRedemptionTxOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      def timeoutTBTCMint(
        timeoutTBTCMintOperation: TimeoutTBTCMintOperation
      )(implicit
        clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.TimeoutTBTCMint(
            timeoutTBTCMintOperation
          )
        )
        response <- executeRequest(request)
      } yield response)

      override def timeoutDepositBTC(
        timeoutDepositBTCOperation: TimeoutDepositBTCOperation
      )(implicit
        clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.TimeoutDepositBTC(
              timeoutDepositBTCOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      override def postDepositBTC(
        postDepositBTCOperation: PostDepositBTCOperation
      )(implicit
        clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.PostDepositBTC(postDepositBTCOperation)
        )
        response <- executeRequest(request)
      } yield response)

      def startPegin(
        startSessionOperation: StartSessionOperation
      )(implicit
        clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.StartSession(startSessionOperation)
          )
          response <- executeRequest(request)
        } yield response)

      def mintingStatus(
        mintingStatusOperation: MintingStatusOperation
      )(implicit
        clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = {
        import cats.implicits._
        mutex.lock.surround(
          for {
            currentView <- currentViewRef.get
            _           <- info"Current view is $currentView"
            _           <- info"Replica count is ${replicaCount.value}"
            currentPrimary = (currentView % replicaCount.value).toInt
            response <- replicaMap(currentPrimary)
              .mintingStatus(mintingStatusOperation, new Metadata())
          } yield response.result match {
            case MintingStatusReply.Result.Empty =>
              Left(
                UnknownError(
                  "This should not happen: Empty response"
                )
              )
            case MintingStatusReply.Result.SessionNotFound(value) =>
              Left(SessionNotFoundError(value.sessionId))
            case MintingStatusReply.Result.MintingStatus(response) =>
              Right {
                MintingStatusResponse(
                  mintingStatus = response.mintingStatus,
                  address = response.address,
                  redeemScript = response.redeemScript
                )
              }
          }
        )
      }

      private def clearVoteTable(
        timestamp: Long
      ): F[Unit] =
        for {
          _ <- Async[F].delay(
            messageResponseMap.remove(
              ConsensusClientMessageId(timestamp)
            )
          )
          _ <- Async[F].delay(
            messageVotersMap.remove(
              ConsensusClientMessageId(timestamp)
            )
          )
        } yield ()

      private def checkVoteResult(
        timestamp: Long
      ): F[Either[BridgeError, BridgeResponse]] = {
        import scala.jdk.CollectionConverters._
        for {
          voteTable <- Async[F].delay(
            messageResponseMap.get(
              ConsensusClientMessageId(timestamp)
            )
          )
          someVotationWinner <- Async[F].delay(
            voteTable
              .entrySet()
              .asScala
              .toList
              .sortBy(_.getValue.longValue())
              .headOption
          )
          winner <- (someVotationWinner match {
            case Some(winner) => // there are votes, check winner
              if (winner.getValue.longValue() < (replicaCount.maxFailures + 1)) {
                trace"Waiting for more votes: ${winner.getValue.longValue()}" >> Async[F].sleep(
                  2.second
                ) >> checkVoteResult(timestamp)
              } else
                debug"We have a winner for ${timestamp}: ${winner.getKey()}" >>
                clearVoteTable(timestamp) >> Async[F].delay(winner.getKey())
            case None => // there are no votes
              trace"No votes yet" >> Async[F].sleep(
                2.second
              ) >> checkVoteResult(timestamp)
          })
        } yield winner
      }

      def retryWithBackoff(
        replica: StateMachineServiceFs2Grpc[F, Metadata],
        request: StateMachineRequest,
        delay: FiniteDuration,
        maxRetries: Int
      ): F[Empty] = {
        for {
          _ <- info"Trying to execute request on another replica, request: ${request.timestamp}"
          response <- replica.executeRequest(request, new Metadata()).handleErrorWith { _ => 
            maxRetries match {
              case 0 => for {
                _ <- error"Max retries reached for request ${request.timestamp}"
                someResponse <- Empty().pure [F]
              } yield someResponse
              case _ => for {
                _ <- Async[F].sleep(delay)
                someResponse <- retryWithBackoff(replica, request, delay * stateMachineConf.retryPolicy.delayMultiplier, maxRetries - 1)
              } yield someResponse
            }
          }
        } yield response
      }

      def executeRequest(
        request: StateMachineRequest
      ): F[Either[BridgeError, BridgeResponse]] = {

      for {
          _ <- info"Sending request to backend"
          // create a new vote table for this request
          _ <- Sync[F].delay(
            messageResponseMap.put(
              ConsensusClientMessageId(request.timestamp),
              new ConcurrentHashMap[Either[
                BridgeError,
                BridgeResponse
              ], LongAdder]()
            )
          )
          // create a new voter table for this request
          _ <- Sync[F].delay(
            messageVotersMap.put(
              ConsensusClientMessageId(request.timestamp),
              new ConcurrentHashMap[Int, Int]()
            )
          )
          currentView <- currentViewRef.get
          _           <- info"Current view is $currentView"
          _           <- info"Replica count is ${replicaCount.value}"
          currentPrimary = (currentView % replicaCount.value).toInt
          _ <- info"Current primary is $currentPrimary"
          _ <- retryWithBackoff(
            replicaMap(currentPrimary),
            request,
            stateMachineConf.retryPolicy.initialDelay, 
            stateMachineConf.retryPolicy.maxRetries
          )
          _ <- trace"Waiting for response from backend"
          replicasWithoutPrimary = replicaMap.filter(_._1 != currentPrimary).values.toList
          someResponse <- Async[F].race(
            Async[F].sleep(stateMachineConf.primaryResponseWait) >> // wait for response
            error"The request ${request.timestamp} timed out, contacting other replicas" >> // timeout
            replicasWithoutPrimary.parTraverse{
            replica => retryWithBackoff(replica, request, stateMachineConf.retryPolicy.initialDelay, stateMachineConf.retryPolicy.maxRetries)
          } >>
            Async[F].sleep(stateMachineConf.otherReplicasResponseWait) >> // wait for response
            (TimeoutError("Timeout waiting for response"): BridgeError)
              .pure[F],
            checkVoteResult(request.timestamp)
          )

        } yield someResponse match {
          case Left(error) => Left(error)
          case Right(response) => response
        }
      }
        

      def prepareRequest(
        operation: StateMachineRequest.Operation
      )(implicit clientNumber: ClientId): F[StateMachineRequest] =
        for {
          timestamp <- Async[F].delay(System.currentTimeMillis())
          request = StateMachineRequest(
            timestamp = timestamp,
            clientNumber = clientNumber.id,
            operation = operation
          )
          signableBytes = request.signableBytes
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            signableBytes
          )
          signedRequest = request.withSignature(
            ByteString.copyFrom(signedBytes)
          )
        } yield signedRequest
    }

  }
}