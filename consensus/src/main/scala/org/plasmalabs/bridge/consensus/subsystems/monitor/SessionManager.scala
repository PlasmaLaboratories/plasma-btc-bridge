package org.plasmalabs.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Sync
import cats.effect.std.Queue
import cats.implicits._
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.consensus.shared.{MiscUtils, PeginSessionInfo, PeginSessionState, SessionInfo}

sealed trait SessionEvent

case class SessionCreated(sessionId: String, sessionInfo: SessionInfo) extends SessionEvent
case class SessionUpdated(sessionId: String, sessionInfo: SessionInfo) extends SessionEvent

trait SessionManagerAlgebra[F[_]] {

  def createNewSession(
    sessionId:   String,
    sessionInfo: SessionInfo
  ): F[String]

  def getSession(
    sessionId: String
  ): F[Option[SessionInfo]]

  def updateSession(
    sessionId:              String,
    sessionInfoTransformer: PeginSessionInfo => SessionInfo
  ): F[Option[SessionInfo]]

  def removeSession(
    sessionId:  String,
    finalState: PeginSessionState
  ): F[Unit]
}

object SessionManagerImpl {

  def makePermanent[F[_]: Sync](
    storageApi: StorageApi[F],
    queue:      Queue[F, SessionEvent]
  ): SessionManagerAlgebra[F] = new SessionManagerAlgebra[F] {

    override def removeSession(
      sessionId:  String,
      finalState: PeginSessionState
    ): F[Unit] =
      updateSession(sessionId, _.copy(mintingBTCState = finalState)).void

    def createNewSession(
      sessionId:   String,
      sessionInfo: SessionInfo
    ): F[String] =
      for {
        _ <- storageApi.insertNewSession(sessionId, sessionInfo)
        _ <- queue.offer(SessionCreated(sessionId, sessionInfo))
      } yield sessionId

    def getSession(
      sessionId: String
    ): F[Option[SessionInfo]] =
      storageApi.getSession(sessionId)

    def updateSession(
      sessionId:              String,
      sessionInfoTransformer: PeginSessionInfo => SessionInfo
    ): F[Option[SessionInfo]] =
      for {
        someSessionInfo <- storageApi.getSession(sessionId)
        someNewSessionInfo = someSessionInfo.flatMap(sessionInfo =>
          MiscUtils.sessionInfoPeginPrism
            .getOption(sessionInfo)
            .map(sessionInfoTransformer)
        )
        _ <- someNewSessionInfo
          .map { x =>
            storageApi.updateSession(sessionId, x) >> queue
              .offer(
                SessionUpdated(sessionId, x)
              )
          }
          .getOrElse(Sync[F].unit)
      } yield someSessionInfo

  }
}
