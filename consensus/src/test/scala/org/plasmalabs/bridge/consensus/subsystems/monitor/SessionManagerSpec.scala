package org.plasmalabs.bridge.consensus.subsystems.monitor

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.plasmalabs.bridge.consensus.shared.persistence.{StorageApi, StorageApiImpl}
import org.plasmalabs.bridge.consensus.shared.{PeginSessionInfo, PeginSessionState}
import org.plasmalabs.bridge.consensus.subsystems.monitor.SessionEvent

import java.nio.file.{Files, Paths}
import java.util.UUID

class SessionManagerSpec extends CatsEffectSuite {

  val sessionInfo = PeginSessionInfo(
    0,
    0,
    "mintTemplateName",
    "redeemAddress",
    "escrowAddress",
    "scriptAsm",
    "sha256",
    1,
    100,
    "claimAddress",
    PeginSessionState.PeginSessionStateWaitingForBTC
  )

  implicit val logger: SelfAwareStructuredLogger[IO] =
    org.typelevel.log4cats.slf4j.Slf4jLogger
      .getLoggerFromName[IO]("test")

  val testdb = "test.db"

  val cleanupDir = ResourceFunFixture[StorageApi[IO]](
    for {
      _ <- Resource
        .make(IO(Files.delete(Paths.get(testdb))).handleError(_ => ()))(_ => IO(Files.delete(Paths.get(testdb))))
      storageApi <- StorageApiImpl.make[IO](testdb)
      _          <- storageApi.initializeStorage().toResource
    } yield storageApi
  )

  cleanupDir.test(
    "SessionManagerAlgebra should create and retrieve a session"
  ) { storageApi =>
    assertIO(
      for {
        queue <- Queue.unbounded[IO, SessionEvent]
        sut = org.plasmalabs.bridge.consensus.subsystems.monitor.SessionManagerImpl.makePermanent[IO](
          storageApi,
          queue
        )
        sessionId        <- IO(UUID.randomUUID().toString)
        _                <- sut.createNewSession(sessionId, sessionInfo)
        retrievedSession <- sut.getSession(sessionId)
      } yield retrievedSession,
      Some(sessionInfo)
    )
  }

  cleanupDir.test(
    "SessionManagerAlgebra should fail to retrieve a non existing session"
  ) { storageApi =>
    assertIO(
      for {
        queue <- Queue.unbounded[IO, SessionEvent]
        sut = org.plasmalabs.bridge.consensus.subsystems.monitor.SessionManagerImpl.makePermanent[IO](
          storageApi,
          queue
        )
        sessionId <- IO(UUID.randomUUID().toString)
        _         <- sut.createNewSession(sessionId, sessionInfo)
        res       <- sut.getSession(UUID.randomUUID().toString)
      } yield res,
      None
    )
  }

}
