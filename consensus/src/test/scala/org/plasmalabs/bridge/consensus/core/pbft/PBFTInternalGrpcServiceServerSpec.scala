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
    ResourceFunFixture[
      (
        PBFTInternalServiceFs2Grpc[IO, Metadata],
        Ref[IO, List[String]],
        Ref[IO, List[String]]
      )
    ] {
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
      implicit val publicApiClientGrpcMap = new PublicApiClientGrpcMap[IO](
        Map.empty
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
      }).flatten
    }

  setupServer.test(
    "checkpoint should throw exception and log error on invalid signature"
  ) { serverAndLogChecker =>
    val (server, errorChecker, _) = serverAndLogChecker
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

  setupServer.test(
    "checkpoint should ignore message and log warning on old message"
  ) { serverAndLogChecker =>
    val (server, _, warningChecker) = serverAndLogChecker

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

  setupServer.test(
    "checkpoint should ignore message if it already exists in the log"
  ) { serverAndLogChecker =>
    val (server, _, warningChecker) = serverAndLogChecker

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

}
