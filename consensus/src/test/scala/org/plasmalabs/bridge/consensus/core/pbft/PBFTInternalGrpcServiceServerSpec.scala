package org.plasmalabs.bridge.consensus.core.pbft

import cats.effect.IO
import cats.effect.kernel.Ref
import com.google.protobuf.ByteString
import fs2.io.process
import io.grpc.Metadata
import munit.CatsEffectSuite
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.plasmalabs.bridge.consensus.core.{PublicApiClientGrpcMap, stateDigest}
import org.plasmalabs.bridge.consensus.pbft.{CheckpointRequest, PBFTInternalServiceFs2Grpc}
import org.plasmalabs.bridge.shared.BridgeCryptoUtils
import org.plasmalabs.bridge.stubs.{BaseLogger, BaseStorageApi}
import org.typelevel.log4cats.Logger

import java.security.Security
import org.plasmalabs.bridge.consensus.pbft.PrePrepareRequest
import org.plasmalabs.bridge.shared.StateMachineRequest
import org.plasmalabs.bridge.shared.ClientId
import org.plasmalabs.bridge.shared.Empty
import org.plasmalabs.bridge.consensus.core.PublicApiClientGrpc
import org.plasmalabs.bridge.consensus.service.StateMachineReply
import org.plasmalabs.bridge.consensus.pbft.PrepareRequest
import org.plasmalabs.bridge.consensus.pbft.CommitRequest
import org.plasmalabs.bridge.consensus.pbft.ViewChangeRequest
import org.plasmalabs.bridge.consensus.pbft.NewViewRequest

class PBFTInternalGrpcServiceServerSpec extends CatsEffectSuite with PBFTInternalGrpcServiceServerSpecAux {

  override def afterAll(): Unit = {
    import cats.implicits._
    (0 to 6).toList
      .map(idx =>
        process
          .ProcessBuilder(
            "rm",
            Seq(
              s"privateKey${idx}.pem",
              s"publicKey${idx}.pem"
            ): _*
          )
          .spawn[IO]
          .use(p =>
            p.stdout
              .through(fs2.text.utf8.decode)
              .compile
              .foldMonoid
              .map(_.trim) >> IO.unit
          )
      )
      .sequence
      .unsafeRunSync()
  }

  override def beforeAll() = {
    import cats.implicits._
    Security.addProvider(new BouncyCastleProvider());
    (for {
      _ <- (0 to 6).toList
        .map(idx =>
          process
            .ProcessBuilder(
              "rm",
              Seq(
                s"privateKey${idx}.pem",
                s"publicKey${idx}.pem"
              ): _*
            )
            .spawn[IO]
            .use(p =>
              p.stdout
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
                .map(_.trim) >> IO.unit
            )
        )
        .sequence
      _ <- (0 to 6).toList
        .map(idx =>
          process
            .ProcessBuilder(
              "openssl",
              Seq(
                "ecparam",
                "-name",
                "secp256k1",
                "-genkey",
                "-noout",
                "-out",
                s"privateKey${idx}.pem"
              ): _*
            )
            .spawn[IO]
            .use(p =>
              p.stdout
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
                .map(_.trim) >> IO.unit
            )
        )
        .sequence
      _ <- (0 to 6).toList
        .map(idx =>
          process
            .ProcessBuilder(
              "openssl",
              Seq(
                "ec",
                "-in",
                s"privateKey${idx}.pem",
                "-pubout",
                "-out",
                s"publicKey${idx}.pem"
              ): _*
            )
            .spawn[IO]
            .use(p =>
              p.stdout
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
                .map(_.trim) >> IO.unit
            )
        )
        .sequence
    } yield ()).unsafeRunSync()
  }

  val setupServer =
    new Fixture[
      (
        PBFTInternalServiceFs2Grpc[IO, Metadata],
        Ref[IO, List[String]],
        Ref[IO, List[String]]
      )
    ]("server") {
      def apply() = {
        Security.addProvider(new BouncyCastleProvider());
        implicit val storageApiStub = new BaseStorageApi() {

          override def getCheckpointMessage(
            sequenceNumber: Long,
            replicaId:      Int
          ): IO[Option[CheckpointRequest]] =
            IO.pure(
              Some(
                CheckpointRequest(
                  sequenceNumber = 0L,
                  digest = ByteString.EMPTY,
                  replicaId = 1,
                  signature = ByteString.EMPTY
                )
              )
            )
        }
        val keyPair = BridgeCryptoUtils.getKeyPair[IO](privateKeyFile).use(IO.pure).unsafeRunSync()
        val temp = new PublicApiClientGrpc[IO] {

          def replyStartPegin(
            timestamp:       Long,
            currentView:     Long,
            startSessionRes: StateMachineReply.Result
          ): IO[Empty] = IO(Empty())
        }
        implicit val publicApiClientGrpcMap = new PublicApiClientGrpcMap[IO](
          Map(
            ClientId(0) -> (temp, keyPair.getPublic())
          )
        )
        import cats.implicits._

        (for {
          loggedError   <- Ref.of[IO, List[String]](List.empty).toResource
          loggedWarning <- Ref.of[IO, List[String]](List.empty).toResource
        } yield {
          implicit val logger: Logger[IO] =
            new BaseLogger() {

              override def error(message: => String): IO[Unit] =
                loggedError.update(_ :+ message)

              override def warn(message: => String): IO[Unit] =
                loggedWarning.update(_ :+ message)
            }
          for {
            serverUnderTest <- createSimpleInternalServer()
          } yield (serverUnderTest, loggedError, loggedWarning)
        }).flatten.use(IO.pure).unsafeRunSync()
      }
    }
  override def munitFixtures = List(setupServer)

  test(
    "checkpoint should throw exception and log error on invalid signature"
  ) {
    val (server, errorChecker, _) = setupServer()
    assertIO(
      for {

        _ <- server.checkpoint(
          CheckpointRequest(
            sequenceNumber = 0L,
            digest = ByteString.EMPTY,
            replicaId = 0,
            signature = ByteString.EMPTY
          ),
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("Signature verification failed"),
      true
    )
  }

  test(
    "checkpoint should throw exception and log error on invalid signature (invalid digest)"
  ) {
    val (server, errorChecker, _) = setupServer()
    import org.plasmalabs.bridge.shared.implicits._
    val checkpointRequest = CheckpointRequest(
      sequenceNumber = -1L,
      digest = ByteString.copyFrom(stateDigest(Map.empty)),
      replicaId = 1
    )
    assertIO(
      for {
        replicaKeyPair <- BridgeCryptoUtils
          .getKeyPair[IO](privateKeyFile)
          .use(IO.pure)
        signedBytes <- BridgeCryptoUtils.signBytes[IO](
          replicaKeyPair.getPrivate(),
          checkpointRequest.signableBytes
        )
        _ <- server.checkpoint(
          checkpointRequest
            .withSignature(
              ByteString.copyFrom(signedBytes)
            )
            .withDigest(ByteString.EMPTY),
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("Signature verification failed"),
      true
    )
  }

  test(
    "checkpoint should ignore message and log warning on old message"
  ) {
    val (server, _, warningChecker) = setupServer()

    import org.plasmalabs.bridge.shared.implicits._
    val checkpointRequest = CheckpointRequest(
      sequenceNumber = -1L,
      digest = ByteString.copyFrom(stateDigest(Map.empty)),
      replicaId = 1
    )
    assertIO(
      for {
        replicaKeyPair <- BridgeCryptoUtils
          .getKeyPair[IO](privateKeyFile)
          .use(IO.pure)
        signedBytes <- BridgeCryptoUtils.signBytes[IO](
          replicaKeyPair.getPrivate(),
          checkpointRequest.signableBytes
        )
        _ <- server.checkpoint(
          checkpointRequest.withSignature(
            ByteString.copyFrom(signedBytes)
          ),
          new Metadata()
        )
        errorMessage <- warningChecker.get
      } yield errorMessage.head.contains(
        "Checkpoint message is older than last stable checkpoint"
      ),
      true
    )
  }

  test(
    "checkpoint should ignore message if it already exists in the log"
  ) {
    val (server, _, warningChecker) = setupServer()

    import org.plasmalabs.bridge.shared.implicits._
    val checkpointRequest = CheckpointRequest(
      sequenceNumber = 100L,
      digest = ByteString.copyFrom(stateDigest(Map.empty)),
      replicaId = 1
    )
    assertIO(
      for {
        replicaKeyPair <- BridgeCryptoUtils
          .getKeyPair[IO](privateKeyFile)
          .use(IO.pure)
        signedBytes <- BridgeCryptoUtils.signBytes[IO](
          replicaKeyPair.getPrivate(),
          checkpointRequest.signableBytes
        )
        _ <- server.checkpoint(
          checkpointRequest.withSignature(
            ByteString.copyFrom(signedBytes)
          ),
          new Metadata()
        )
        errorMessage <- warningChecker.get
      } yield errorMessage.head.contains(
        "The log is already present"
      ),
      true
    )
  }

  test(
    "prePrepare should throw exception and log error on invalid request signature"
  ) {
    val (server, errorChecker, _) = setupServer()
    val preprepareReq = PrePrepareRequest(
      sequenceNumber = 0L,
      digest = ByteString.EMPTY,
      viewNumber = 0L,
      signature = ByteString.EMPTY,
      payload = Some(StateMachineRequest())
    )
    assertIO(
      for {
        _ <- server.prePrepare(
          preprepareReq,
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("Invalid request signature"),
      true
    )
  }
  
  test(
    "prePrepare should throw exception and log error on invalid pre-prepare payload signature"
  ) {
    val (server, errorChecker, _) = setupServer()
    import org.plasmalabs.bridge.shared.implicits._
    val preprepareReq = PrePrepareRequest(
      sequenceNumber = 0L,
      digest = ByteString.EMPTY,
      signature = ByteString.EMPTY,
      viewNumber = 0L
    )
    val payload = StateMachineRequest()
    assertIO(
      for {
        replicaKeyPair <- BridgeCryptoUtils
          .getKeyPair[IO](privateKeyFile)
          .use(IO.pure)
        signedBytes <- BridgeCryptoUtils.signBytes[IO](
          replicaKeyPair.getPrivate(),
          payload.signableBytes
        )
        _ <- server.prePrepare(
          preprepareReq.withPayload(payload.withSignature(ByteString.copyFrom(signedBytes))),
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("Invalid pre-prepare signature"),
      true
    )
  }
  test(
    "prepare should throw exception and log error on invalid request signature"
  ) {
    val (server, errorChecker, _) = setupServer()
    val prepareReq = PrepareRequest(
      sequenceNumber = 0L,
      digest = ByteString.EMPTY,
      viewNumber = 0L,
      signature = ByteString.EMPTY
    )
    assertIO(
      for {
        _ <- server.prepare(
          prepareReq,
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("Invalid Prepare signature"),
      true
    )
  }
  test(
    "commit should throw exception and log error on invalid request signature"
  ) {
    val (server, errorChecker, _) = setupServer()
    val commitReq = CommitRequest(
      sequenceNumber = 0L,
      digest = ByteString.EMPTY,
      viewNumber = 0L,
      signature = ByteString.EMPTY
    )
    assertIO(
      for {
        _ <- server.commit(
          commitReq,
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("Invalid commit signature"),
      true
    )
  }
  test(
    "viewChange should throw exception and log error on invalid request signature"
  ) {
    val (server, errorChecker, _) = setupServer()
    val vcReq = ViewChangeRequest()
    assertIO(
      for {
        _ <- server.viewChange(
          vcReq,
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("View Change: An invalid signature was found in the view change request"),
      true
    )
  }
  test(
    "newView should throw exception and log error on invalid request signature"
  ) {
    val (server, errorChecker, _) = setupServer()
    val nvReq = NewViewRequest()
    assertIO(
      for {
        _ <- server.newView(
          nvReq,
          new Metadata()
        )
        errorMessage <- errorChecker.get
      } yield errorMessage.head.contains("NewViewActivity: NewView signature validation failed"),
      true
    )
  }
}
