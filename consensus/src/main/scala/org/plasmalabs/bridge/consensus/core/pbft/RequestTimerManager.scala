package org.plasmalabs.bridge.consensus.core.pbft

import cats.data.OptionT
import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Queue
import org.plasmalabs.bridge.shared.ClientId
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.Duration

case class RequestIdentifier(
  cliendId:  ClientId,
  timestamp: Long
)

trait RequestTimerManager[F[_]] {

  def startTimer(timerIdentifier: RequestIdentifier): F[Unit]

  def clearTimer(timerIdentifier: RequestIdentifier): F[Unit]

  def hasExpiredTimer(): F[Boolean]

  def resetAllTimers(): F[Unit]

}

object RequestTimerManagerImpl {

  def make[F[_]: Async: Logger](
    requestTimeout: Duration,
    queue:          Queue[F, PBFTInternalEvent]
  ): F[RequestTimerManager[F]] = {
    import cats.implicits._
    for {
      runningTimers <- Ref.of[F, Set[RequestIdentifier]](Set())
      expiredTimers <- Ref.of[F, Set[RequestIdentifier]](Set())
    } yield new RequestTimerManager[F] {

      override def startTimer(timerIdentifier: RequestIdentifier): F[Unit] =
        for {
          _ <- runningTimers.update(_ + timerIdentifier)
          _ <- Async[F].start(
            for {
              _   <- Async[F].sleep(requestTimeout)
              map <- runningTimers.getAndUpdate(_ - timerIdentifier)
              _ <-
                if (map.contains(timerIdentifier)) {
                  expiredTimers.update(_ + timerIdentifier) >>
                  queue.offer(PBFTTimeoutEvent(timerIdentifier))
                } else {
                  Async[F].unit
                }
            } yield ()
          )
        } yield ()

      override def clearTimer(timerIdentifier: RequestIdentifier): F[Unit] =
        for {
          _ <- runningTimers.update(_ - timerIdentifier)
          _ <- expiredTimers.update(_ - timerIdentifier)
        } yield ()

      override def hasExpiredTimer(): F[Boolean] = {
        import org.typelevel.log4cats.syntax._
        for {
          setExpired <- expiredTimers.get
          _ <- OptionT
            .whenF(setExpired.size > 0)(
              error"Timer expired: ${setExpired.take(3)}${if (setExpired.size > 3) "..." else ""}"
            )
            .getOrElse(Async[F].unit)
        } yield setExpired.nonEmpty
      }

      def resetAllTimers(): F[Unit] =
        for {
          _ <- runningTimers.set(Set())
          _ <- expiredTimers.set(Set())
        } yield ()
    }
  }
}
