package org.plasmalabs.bridge.consensus.shared.persistence

import cats.effect.kernel.{Resource, Sync}
import cats.implicits._
import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.InternalSignature
import org.plasmalabs.bridge.consensus.pbft.{CheckpointRequest, CommitRequest, PrePrepareRequest, PrepareRequest}
import org.plasmalabs.bridge.consensus.shared.{MiscUtils, PeginSessionInfo, PeginSessionState, SessionInfo}
import org.plasmalabs.bridge.consensus.subsystems.monitor.BlockchainEvent
import org.plasmalabs.bridge.shared.StateMachineRequest
import org.plasmalabs.sdk.utils.Encoding

import java.sql.DriverManager

trait StorageApi[F[_]] {

  def cleanLog(
    sequenceNumber: Long
  ): F[Unit]

  def getPrePrepareMessage(
    viewNumber:     Long,
    sequenceNumber: Long
  ): F[Option[PrePrepareRequest]]

  def getPrePrepareMessagesFromSeqNumber(
    viewNumber:     Long,
    sequenceNumber: Long
  ): F[Seq[PrePrepareRequest]]

  def getPrepareMessage(
    viewNumber:     Long,
    sequenceNumber: Long,
    replicaId:      Int
  ): F[Option[PrepareRequest]]

  def getPrepareMessages(
    viewNumber:     Long,
    sequenceNumber: Long
  ): F[Seq[PrepareRequest]]

  def getCommitMessages(
    viewNumber:     Long,
    sequenceNumber: Long
  ): F[Seq[CommitRequest]]

  def getCommitMessage(
    viewNumber:     Long,
    sequenceNumber: Long,
    replicaId:      Int
  ): F[Option[CommitRequest]]

  def getCheckpointMessage(
    sequenceNumber: Long,
    replicaId:      Int
  ): F[Option[CheckpointRequest]]

  def insertPrePrepareMessage(
    prePrepare: PrePrepareRequest
  ): F[Boolean]

  def insertCheckpointMessage(
    checkpointRequest: CheckpointRequest
  ): F[Boolean]

  def insertPrepareMessage(
    prepare: PrepareRequest
  ): F[Boolean]

  def insertCommitMessage(
    commit: CommitRequest
  ): F[Boolean]

  def insertNewSession(
    sessionId:   String,
    sessionInfo: SessionInfo
  ): F[Unit]

  def getSession(sessionId: String): F[Option[SessionInfo]]

  def updateSession(
    sessionId:   String,
    sessionInfo: SessionInfo
  ): F[Unit]

  def insertSignature(
    txId:      String,
    signature: String,
    timestamp: Long
  ): F[Boolean]

  def getSignature(
    txId: String
  ): F[Option[InternalSignature]]

  def insertBlockchainEvent(event: BlockchainEvent): F[Unit]

  def initializeStorage(): F[Unit]
}

object StorageApiImpl {

  private def createResource[F[_]: Sync](fileName: String) = Resource
    .make(
      {
        // Without this line, repeated runs fail with "No suitable driver found for jdbc:sqlite:..."
        Class.forName("org.sqlite.JDBC")
        Sync[F].delay(
          DriverManager.getConnection(
            s"jdbc:sqlite:${fileName}"
          )
        )
      }
    )(conn => Sync[F].delay(conn.close()))

  def make[F[_]: Sync](fileName: String): Resource[F, StorageApi[F]] =
    for {
      conn <- createResource(fileName)
    } yield new StorageApi[F] {

      override def getCommitMessages(
        viewNumber:     Long,
        sequenceNumber: Long
      ): F[Seq[CommitRequest]] = {
        val selectCommitStmnt =
          s"SELECT * FROM commit_message WHERE view_number = ${viewNumber} AND sequence_number = ${sequenceNumber}"
        statementResource.use { stmnt =>
          for {
            rs <- Sync[F]
              .blocking(
                stmnt.executeQuery(
                  selectCommitStmnt
                )
              )
            commitMessages <- Sync[F].blocking {
              val commitMessages =
                scala.collection.mutable.ArrayBuffer.empty[CommitRequest]
              while (rs.next()) {
                val digest = rs.getString("digest")
                val signature = rs.getString("signature")
                val replicaId = rs.getInt("replica_id")
                val commit = CommitRequest(
                  viewNumber = viewNumber,
                  sequenceNumber = sequenceNumber,
                  digest = ByteString.copyFrom(
                    Encoding.decodeFromHex(digest).toOption.get
                  ),
                  replicaId = replicaId,
                  signature = ByteString.copyFrom(
                    Encoding.decodeFromHex(signature).toOption.get
                  )
                )
                commitMessages += commit
              }
              commitMessages.toSeq
            }
          } yield commitMessages
        }
      }

      override def getCommitMessage(
        viewNumber:     Long,
        sequenceNumber: Long,
        replicaId:      Int
      ): F[Option[CommitRequest]] = {
        val selectCommitStmnt =
          s"SELECT * FROM commit_message WHERE view_number = ${viewNumber} AND sequence_number = ${sequenceNumber} AND replica_id = ${replicaId}"
        statementResource.use { stmnt =>
          for {
            rs <- Sync[F]
              .blocking(
                stmnt.executeQuery(
                  selectCommitStmnt
                )
              )
            commitMessages <- Sync[F].blocking {
              val commitMessages =
                scala.collection.mutable.ArrayBuffer.empty[CommitRequest]
              while (rs.next()) {
                val digest = rs.getString("digest")
                val signature = rs.getString("signature")
                val commit = CommitRequest(
                  viewNumber = viewNumber,
                  sequenceNumber = sequenceNumber,
                  digest = ByteString.copyFrom(
                    Encoding.decodeFromHex(digest).toOption.get
                  ),
                  replicaId = replicaId,
                  signature = ByteString.copyFrom(
                    Encoding.decodeFromHex(signature).toOption.get
                  )
                )
                commitMessages += commit
              }
              commitMessages.toSeq
            }
          } yield commitMessages.headOption
        }
      }

      override def insertCommitMessage(commit: CommitRequest): F[Boolean] = {
        val insertCommitStmnt =
          "INSERT INTO commit_message (" +
          "view_number, " +
          "sequence_number, " +
          "replica_id, " +
          "digest, " +
          "signature" +
          ") VALUES " +
          "(" +
          s"${commit.viewNumber}," +
          s"${commit.sequenceNumber}," +
          s"${commit.replicaId}," +
          s"'${Encoding.encodeToHex(commit.digest.toByteArray)}'," +
          s"'${Encoding.encodeToHex(commit.signature.toByteArray)}'" +
          ")"
        statementResource.use { stmnt =>
          Sync[F]
            .blocking(
              stmnt.execute(insertCommitStmnt)
            )
        }
      }

      override def getPrepareMessage(
        viewNumber:     Long,
        sequenceNumber: Long,
        replicaId:      Int
      ): F[Option[PrepareRequest]] = {
        val selectPrepareStmnt =
          s"SELECT * FROM prepare_message WHERE view_number = ${viewNumber} AND sequence_number = ${sequenceNumber} AND replica_id = ${replicaId}"
        statementResource.use { stmnt =>
          for {
            rs <- Sync[F]
              .blocking(
                stmnt.executeQuery(
                  selectPrepareStmnt
                )
              )
            prepareMessages <- Sync[F].blocking {
              val prepareMessages =
                scala.collection.mutable.ArrayBuffer.empty[PrepareRequest]
              while (rs.next()) {
                val digest = rs.getString("digest")
                val signature = rs.getString("signature")
                val prepare = PrepareRequest(
                  viewNumber = viewNumber,
                  sequenceNumber = sequenceNumber,
                  digest = ByteString.copyFrom(
                    Encoding.decodeFromHex(digest).toOption.get
                  ),
                  replicaId = replicaId,
                  signature = ByteString.copyFrom(
                    Encoding.decodeFromHex(signature).toOption.get
                  )
                )
                prepareMessages += prepare
              }
              prepareMessages.toSeq
            }
          } yield prepareMessages.headOption
        }
      }

      override def getPrepareMessages(
        viewNumber:     Long,
        sequenceNumber: Long
      ): F[Seq[PrepareRequest]] = {
        val selectPrepareStmnt =
          s"SELECT * FROM prepare_message WHERE view_number = ${viewNumber} AND sequence_number = ${sequenceNumber}"
        statementResource.use { stmnt =>
          for {
            rs <- Sync[F]
              .blocking(
                stmnt.executeQuery(
                  selectPrepareStmnt
                )
              )
            prepareMessages <- Sync[F].blocking {
              val prepareMessages =
                scala.collection.mutable.ArrayBuffer.empty[PrepareRequest]
              while (rs.next()) {
                val digest = rs.getString("digest")
                val signature = rs.getString("signature")
                val replicaId = rs.getInt("replica_id")
                val prepare = PrepareRequest(
                  viewNumber = viewNumber,
                  sequenceNumber = sequenceNumber,
                  digest = ByteString.copyFrom(
                    Encoding.decodeFromHex(digest).toOption.get
                  ),
                  replicaId = replicaId,
                  signature = ByteString.copyFrom(
                    Encoding.decodeFromHex(signature).toOption.get
                  )
                )
                prepareMessages += prepare
              }
              prepareMessages.toSeq
            }
          } yield prepareMessages
        }
      }

      def getPrePrepareMessagesFromSeqNumber(
        viewNumber:     Long,
        sequenceNumber: Long
      ): F[Seq[PrePrepareRequest]] = {
        val selectPrePrepareStmnt =
          s"SELECT * FROM pre_prepare_message WHERE view_number = ${viewNumber} AND sequence_number > ${sequenceNumber}"
        statementResource.use { stmnt =>
          for {
            rs <- Sync[F]
              .blocking(
                stmnt.executeQuery(
                  selectPrePrepareStmnt
                )
              )
            prePrepareMessages <- Sync[F].blocking {
              val prePrepareMessages =
                scala.collection.mutable.ArrayBuffer.empty[PrePrepareRequest]
              while (rs.next()) {
                val digest = rs.getString("digest")
                val signature = rs.getString("signature")
                val payload = rs.getString("payload")
                val prePrepare = PrePrepareRequest(
                  viewNumber,
                  sequenceNumber,
                  ByteString.copyFrom(
                    Encoding.decodeFromHex(digest).toOption.get
                  ),
                  ByteString.copyFrom(
                    Encoding.decodeFromHex(signature).toOption.get
                  ),
                  Encoding
                    .decodeFromHex(payload)
                    .map(StateMachineRequest.parseFrom(_))
                    .toOption
                )
                prePrepareMessages += prePrepare
              }
              prePrepareMessages.toSeq
            }
          } yield prePrepareMessages
        }
      }

      override def insertPrepareMessage(prepare: PrepareRequest): F[Boolean] =
        statementResource.use { stmnt =>
          val insertPrepareStmnt =
            "INSERT INTO prepare_message (" +
            "view_number, " +
            "sequence_number, " +
            "replica_id, " +
            "digest, " +
            "signature " +
            ") VALUES " +
            "(" +
            s"${prepare.viewNumber}, " +
            s"${prepare.sequenceNumber}, " +
            s"${prepare.replicaId}, " +
            s"'${Encoding.encodeToHex(prepare.digest.toByteArray)}', " +
            s"'${Encoding.encodeToHex(prepare.signature.toByteArray)}'" +
            ")"

          Sync[F]
            .blocking(
              stmnt.execute(insertPrepareStmnt)
            )
        }

      private def selectPrePrepareStmnt(
        viewNumber:     Long,
        sequenceNumber: Long
      ) =
        s"SELECT * FROM pre_prepare_message WHERE view_number = ${viewNumber} AND sequence_number = ${sequenceNumber}"

      def getPrePrepareMessage(
        viewNumber:     Long,
        sequenceNumber: Long
      ): F[Option[PrePrepareRequest]] =
        statementResource.use { stmnt =>
          for {
            rs <- Sync[F]
              .blocking(
                stmnt.executeQuery(
                  selectPrePrepareStmnt(viewNumber, sequenceNumber)
                )
              )
            hasNext <- Sync[F].blocking(rs.next())
          } yield
            if (hasNext) {
              val digest = rs.getString("digest")
              val signature = rs.getString("signature")
              val payload = rs.getString("payload")
              val prePrepare = PrePrepareRequest(
                viewNumber,
                sequenceNumber,
                ByteString.copyFrom(
                  Encoding.decodeFromHex(digest).toOption.get
                ),
                ByteString.copyFrom(
                  Encoding.decodeFromHex(signature).toOption.get
                ),
                Encoding
                  .decodeFromHex(payload)
                  .map(StateMachineRequest.parseFrom(_))
                  .toOption
              )
              Some(prePrepare)
            } else {
              None
            }
        }

      def insertPrePrepareMessage(
        prePrepare: PrePrepareRequest
      ): F[Boolean] = {
        val insertPrePrepareStmnt =
          "INSERT INTO pre_prepare_message (" +
          "view_number, " +
          "sequence_number, " +
          "digest, " +
          "signature, " +
          "payload" +
          ") VALUES " +
          "(" +
          s"${prePrepare.viewNumber}," +
          s"${prePrepare.sequenceNumber}," +
          s"'${Encoding.encodeToHex(prePrepare.digest.toByteArray)}'," +
          s"'${Encoding.encodeToHex(prePrepare.signature.toByteArray)}'," +
          s"'${Encoding.encodeToHex(prePrepare.payload.get.toByteArray)}'" +
          ")"

        statementResource.use { stmnt =>
          Sync[F]
            .blocking(
              stmnt.execute(insertPrePrepareStmnt)
            )
        }
      }

      override def insertNewSession(
        sessionId: String,
        sInfo:     SessionInfo
      ): F[Unit] =
        MiscUtils.sessionInfoPeginPrism.getOption(
          sInfo
        ) match {
          case None =>
            Sync[F].raiseError(
              new Exception(
                "SessionInfo is not a PeginSessionInfo"
              )
            )
          case Some(sessionInfo) =>
            val insertSessionStmnt =
              "INSERT INTO sessions_view (" +
              "session_id, " +
              "session_type, " +
              "btc_wallet_idx, " +
              "bridge_wallet_idx, " +
              "mint_template, " +
              "redeem_address, " +
              "escrow_address, " +
              "script_asm, " +
              "sha256, " +
              "min_height, " +
              "max_height, " +
              "claim_address, " +
              "minting_status " +
              " ) VALUES " +
              "(" +
              s"'${sessionId}'," +
              "'pegin'," +
              s"${sessionInfo.btcPeginCurrentWalletIdx}," +
              s"${sessionInfo.btcBridgeCurrentWalletIdx}," +
              s"'${sessionInfo.mintTemplateName}'," +
              s"'${sessionInfo.redeemAddress}'," +
              s"'${sessionInfo.escrowAddress}'," +
              s"'${sessionInfo.scriptAsm}'," +
              s"'${sessionInfo.sha256}'," +
              s"${sessionInfo.minHeight}," +
              s"${sessionInfo.maxHeight}," +
              s"'${sessionInfo.claimAddress}'," +
              s"'${sessionInfo.mintingBTCState.toString()}'" +
              ")"
            import cats.implicits._
            statementResource.use { stmnt =>
              Sync[F]
                .blocking(
                  stmnt.execute(insertSessionStmnt)
                )
                .void
                .handleErrorWith { e =>
                  Sync[F].delay(e.printStackTrace()) >>
                  Sync[F].raiseError(
                    new Exception(
                      s"Failed to insert session into database: ${e.getMessage}"
                    )
                  )
                }
            }
        }

      def getSession(sessionId: String): F[Option[SessionInfo]] = {
        val selectSessionStmnt =
          s"SELECT * FROM sessions_view WHERE session_id = '${sessionId}'"
        import cats.implicits._
        statementResource.use { stmnt =>
          Sync[F]
            .blocking(
              stmnt.executeQuery(selectSessionStmnt)
            )
            .map { rs =>
              if (rs.next()) {
                if (rs.getString("session_type") != "pegin") {
                  throw new Exception(
                    "Session type is not pegin"
                  )
                } else
                  Some(
                    PeginSessionInfo(
                      rs.getInt("btc_wallet_idx"),
                      rs.getInt("bridge_wallet_idx"),
                      rs.getString("mint_template"),
                      rs.getString("redeem_address"),
                      rs.getString("escrow_address"),
                      rs.getString("script_asm"),
                      rs.getString("sha256"),
                      rs.getLong("min_height"),
                      rs.getLong("max_height"),
                      rs.getString("claim_address"),
                      PeginSessionState
                        .withName(rs.getString("minting_status"))
                        .get
                    ): SessionInfo
                  )
              } else {
                None
              }
            }
            .handleErrorWith { e =>
              Sync[F].delay(e.printStackTrace()) >>
              Sync[F].raiseError(
                new Exception(
                  s"Failed to get session from database: ${e.getMessage}"
                )
              )
            }
        }
      }

      override def updateSession(
        sessionId:   String,
        sessionInfo: SessionInfo
      ): F[Unit] =
        MiscUtils.sessionInfoPeginPrism.getOption(
          sessionInfo
        ) match {
          case None =>
            Sync[F].raiseError(
              new Exception(
                "SessionInfo is not a PeginSessionInfo"
              )
            )
          case Some(sessionInfo) =>
            val updateSessionStmnt =
              "UPDATE sessions_view SET " +
              s"btc_wallet_idx = ${sessionInfo.btcPeginCurrentWalletIdx}, " +
              s"bridge_wallet_idx = ${sessionInfo.btcBridgeCurrentWalletIdx}, " +
              s"mint_template = '${sessionInfo.mintTemplateName}', " +
              s"redeem_address = '${sessionInfo.redeemAddress}', " +
              s"escrow_address = '${sessionInfo.escrowAddress}', " +
              s"script_asm = '${sessionInfo.scriptAsm}', " +
              s"sha256 = '${sessionInfo.sha256}', " +
              s"min_height = ${sessionInfo.minHeight}, " +
              s"max_height = ${sessionInfo.maxHeight}, " +
              s"claim_address = '${sessionInfo.claimAddress}', " +
              s"minting_status = '${sessionInfo.mintingBTCState.toString()}' " +
              s"WHERE session_id = '${sessionId}'"
            import cats.implicits._
            statementResource.use { stmnt =>
              Sync[F]
                .blocking(
                  stmnt.execute(updateSessionStmnt)
                )
                .void
                .handleErrorWith { e =>
                  Sync[F].delay(e.printStackTrace()) >>
                  Sync[F].raiseError(
                    new Exception(
                      s"Failed to update session in database: ${e.getMessage}"
                    )
                  )
                }
            }
        }

      val statementResource =
        Resource.make(Sync[F].blocking(conn.createStatement()))(stmnt => Sync[F].blocking(stmnt.close()))

      def insertionStmnt(streamId: String, serializedEvent: String) =
        s"INSERT INTO events (stream_id, event_data) VALUES ('$streamId', '${serializedEvent}')"

      val createEvtTableStmnt =
        "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY," +
        " stream_id TEXT NOT NULL,  event_data TEXT NOT NULL)"

      val createSessionTableStmt =
        "CREATE TABLE IF NOT EXISTS sessions_view (" +
        "session_id     TEXT PRIMARY KEY, " +
        "session_type   TEXT NOT NULL, " +
        "btc_wallet_idx INTEGER NOT NULL, " +
        "bridge_wallet_idx INTEGER NOT NULL, " +
        "mint_template  TEXT NOT NULL, " +
        "redeem_address        TEXT NOT NULL, " +
        "escrow_address        TEXT NOT NULL, " +
        "script_asm            TEXT NOT NULL, " +
        "sha256                TEXT NOT NULL, " +
        "min_height            INTEGER NOT NULL, " +
        "max_height            INTEGER NOT NULL, " +
        "claim_address         TEXT NOT NULL, " +
        "minting_status TEXT NOT NULL" +
        ")"

      val createPrePrepareTableStmt =
        "CREATE TABLE IF NOT EXISTS pre_prepare_message (" +
        "view_number INTEGER NOT NULL, " +
        "sequence_number INTEGER NOT NULL, " +
        "digest TEXT NOT NULL, " +
        "signature TEXT NOT NULL, " +
        "payload TEXT NOT NULL, " +
        "PRIMARY KEY (view_number, sequence_number)" +
        ")"

      val createPrepareTableStmt =
        "CREATE TABLE IF NOT EXISTS prepare_message (" +
        "view_number INTEGER NOT NULL, " +
        "sequence_number INTEGER NOT NULL, " +
        "digest TEXT NOT NULL, " +
        "replica_id INTEGER NOT NULL, " +
        "signature TEXT NOT NULL, " +
        "PRIMARY KEY (view_number, sequence_number, replica_id)" +
        ")"

      val createCommitTableStmt =
        "CREATE TABLE IF NOT EXISTS commit_message (" +
        "view_number INTEGER NOT NULL, " +
        "sequence_number INTEGER NOT NULL, " +
        "replica_id INTEGER NOT NULL, " +
        "digest TEXT NOT NULL, " +
        "signature TEXT NOT NULL, " +
        "PRIMARY KEY (view_number, sequence_number, replica_id)" +
        ")"

      val createCheckpointTableStmnt =
        "CREATE TABLE IF NOT EXISTS checkpoint_message (" +
        "sequence_number INTEGER NOT NULL, " +
        "digest TEXT NOT NULL, " +
        "replica_id INTEGER NOT NULL, " +
        "signature TEXT NOT NULL, " +
        "PRIMARY KEY (sequence_number, replica_id)" +
        ")"

      val createSignatureTableStmt =
        "CREATE TABLE IF NOT EXISTS signatures (" +
        "tx_id TEXT NOT NULL," +
        "signature TEXT NOT NULL," +
        "timestamp TEXT NOT NULL, " +
        "PRIMARY KEY (tx_id)" +
        ")"

      def insertCheckpointMessage(
        checkpointRequest: CheckpointRequest
      ): F[Boolean] = {
        val insertCheckpointStmnt =
          "INSERT INTO checkpoint_message (" +
          "sequence_number, " +
          "digest, " +
          "replica_id, " +
          "signature" +
          ") VALUES " +
          "(" +
          s"${checkpointRequest.sequenceNumber}," +
          s"'${Encoding.encodeToHex(checkpointRequest.digest.toByteArray)}'," +
          s"${checkpointRequest.replicaId}," +
          s"'${Encoding.encodeToHex(checkpointRequest.signature.toByteArray)}'" +
          ")"
        statementResource.use { stmnt =>
          Sync[F]
            .blocking(
              stmnt.execute(insertCheckpointStmnt)
            )
        }
      }

      override def cleanLog(sequenceNumber: Long): F[Unit] = {
        val deletePrePrepareStmnt =
          s"DELETE FROM pre_prepare_message WHERE sequence_number < ${sequenceNumber}"
        val deletePrepareStmnt =
          s"DELETE FROM prepare_message WHERE sequence_number < ${sequenceNumber}"
        val deleteCommitStmnt =
          s"DELETE FROM commit_message WHERE sequence_number < ${sequenceNumber}"
        val deleteCheckpointStmnt =
          s"DELETE FROM checkpoint_message WHERE sequence_number < ${sequenceNumber}"
        statementResource.use { stmnt =>
          Sync[F]
            .blocking(
              stmnt.execute(deletePrePrepareStmnt)
            ) >> Sync[F]
            .blocking(
              stmnt.execute(deletePrepareStmnt)
            ) >> Sync[F]
            .blocking(
              stmnt.execute(deleteCommitStmnt)
            ) >> Sync[F]
            .blocking(
              stmnt.execute(deleteCheckpointStmnt)
            )
        }
      }

      override def getCheckpointMessage(
        sequenceNumber: Long,
        replicaId:      Int
      ): F[Option[CheckpointRequest]] = {
        val selectCheckpointStmnt =
          s"SELECT * FROM checkpoint_message WHERE sequence_number = ${sequenceNumber} AND replica_id = ${replicaId}"
        statementResource.use { stmnt =>
          for {
            rs <- Sync[F]
              .blocking(
                stmnt.executeQuery(
                  selectCheckpointStmnt
                )
              )
            hasNext <- Sync[F].blocking(rs.next())
          } yield
            if (hasNext) {
              val digest = rs.getString("digest")
              val signature = rs.getString("signature")
              val checkpoint = CheckpointRequest(
                sequenceNumber = sequenceNumber,
                digest = ByteString.copyFrom(
                  Encoding.decodeFromHex(digest).toOption.get
                ),
                replicaId = replicaId,
                signature = ByteString.copyFrom(
                  Encoding.decodeFromHex(signature).toOption.get
                )
              )
              Some(checkpoint)
            } else {
              None
            }
        }
      }

      val BLOCKCHAIN_EVENT_ID = "blockchain_event"

      override def insertBlockchainEvent(
        event: BlockchainEvent
      ): F[Unit] =
        statementResource.use { stmnt =>
          import cats.implicits._
          val serializedEvent = toProtobuf(event).toByteArray
          val encodedEvent = BaseEncoding
            .base64()
            .encode(serializedEvent)
          Sync[F]
            .blocking(
              stmnt.execute(insertionStmnt(BLOCKCHAIN_EVENT_ID, encodedEvent))
            )
            .void
            .handleErrorWith { e =>
              Sync[F].delay(e.printStackTrace()) >>
              Sync[F].raiseError(
                new Exception(
                  s"Failed to insert event into database: ${e.getMessage}"
                )
              )
            }
        }

      override def insertSignature(
        txId:      String,
        signature: String,
        timestamp: Long
      ): F[Boolean] = {
        val insertSignatureStmnt =
          "INSERT OR REPLACE INTO signatures (tx_id, signature, timestamp) VALUES" +
          s"('${txId}','${signature}','${timestamp}')"

        statementResource.use { stmnt =>
          Sync[F]
            .blocking(
              stmnt.execute(insertSignatureStmnt)
            )
        }
      }

      override def getSignature(
        txId: String
      ): F[Option[InternalSignature]] = {
        val selectSignatureStmnt =
          "SELECT * FROM signatures WHERE tx_id = ?"

        Resource
          .make(
            Sync[F].blocking(conn.prepareStatement(selectSignatureStmnt))
          )(stmt => Sync[F].blocking(stmt.close()))
          .use { prepStmt =>
            for {
              _  <- Sync[F].blocking(prepStmt.setString(1, txId))
              rs <- Sync[F].blocking(prepStmt.executeQuery())
              result <- Sync[F].blocking {
                if (rs.next()) {
                  Some(
                    InternalSignature(
                      txId = rs.getString("tx_id"),
                      signature = rs.getString("signature"),
                      timestamp = rs.getLong("timestamp")
                    )
                  )
                } else None
              }
            } yield result
          }
      }

      override def initializeStorage(): F[Unit] = statementResource.use { stmnt =>
        Sync[F].blocking(
          stmnt.execute(createEvtTableStmnt)
        ) >> Sync[F].blocking(
          stmnt.execute(createSessionTableStmt)
        ) >> Sync[F].blocking(
          stmnt.execute(createPrePrepareTableStmt)
        ) >> Sync[F].blocking(
          stmnt.execute(createPrepareTableStmt)
        ) >> Sync[F].blocking(
          stmnt.execute(createCommitTableStmt)
        ) >> Sync[F].blocking(
          stmnt.execute(createCheckpointTableStmnt)
        ) >> Sync[F].blocking(
          stmnt.execute(createSignatureTableStmt)
        )
      }

    }

}
