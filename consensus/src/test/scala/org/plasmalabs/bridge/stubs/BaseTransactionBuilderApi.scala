package org.plasmalabs.sdk.cli.mockbase

import com.google.protobuf.ByteString
import com.google.protobuf.struct.Struct
import quivr.models.Int128
import org.plasmalabs.indexer.services.Txo
import org.plasmalabs.sdk.builders.{BuilderError, TransactionBuilderApi}
import org.plasmalabs.sdk.models.Event.{GroupPolicy, SeriesPolicy}
import org.plasmalabs.sdk.models.box.{AssetMintingStatement, Attestation, FungibilityType, Lock, QuantityDescriptorType}
import org.plasmalabs.sdk.models.transaction.{IoTransaction, UnspentTransactionOutput}
import org.plasmalabs.sdk.models.{Datum, GroupId, LockAddress, SeriesId, TransactionOutputAddress}
import org.plasmalabs.sdk.syntax.ValueTypeIdentifier

class BaseTransactionBuilderApi[F[_]] extends TransactionBuilderApi[F] {

  override def buildAssetMergeTransaction(
    utxosToMerge:           Seq[TransactionOutputAddress],
    txos:                   Seq[Txo],
    locks:                  Map[LockAddress, Lock.Predicate],
    fee:                    Long,
    mergedAssetLockAddress: LockAddress,
    changeAddress:          LockAddress,
    ephemeralMetadata:      Option[Struct],
    commitment:             Option[ByteString]
  ): F[Either[BuilderError, IoTransaction]] = ???

  override def buildTransferAllTransaction(
    txos:                 Seq[Txo],
    lockPredicateFrom:    Lock.Predicate,
    recipientLockAddress: LockAddress,
    changeLockAddress:    LockAddress,
    fee:                  Long,
    tokenIdentifier:      Option[ValueTypeIdentifier]
  ): F[Either[BuilderError, IoTransaction]] = ???

  override def buildTransferAmountTransaction(
    tokenIdentifier:      ValueTypeIdentifier,
    txos:                 Seq[Txo],
    lockPredicateFrom:    Lock.Predicate,
    amount:               Long,
    recipientLockAddress: LockAddress,
    changeLockAddress:    LockAddress,
    fee:                  Long
  ): F[Either[BuilderError, IoTransaction]] = ???

  override def groupOutput(
    lockAddress: LockAddress,
    quantity:    Int128,
    groupId:     GroupId,
    fixedSeries: Option[SeriesId]
  ): F[UnspentTransactionOutput] = ???

  override def seriesOutput(
    lockAddress:        LockAddress,
    quantity:           Int128,
    seriesId:           SeriesId,
    tokenSupply:        Option[Int],
    fungibility:        FungibilityType,
    quantityDescriptor: QuantityDescriptorType
  ): F[UnspentTransactionOutput] = ???

  override def assetOutput(
    lockAddress:            LockAddress,
    quantity:               Int128,
    groupId:                GroupId,
    seriesId:               SeriesId,
    fungibilityType:        FungibilityType,
    quantityDescriptorType: QuantityDescriptorType,
    metadata:               Option[Struct],
    commitment:             Option[ByteString]
  ): F[UnspentTransactionOutput] = ???

  override def buildGroupMintingTransaction(
    txos:              Seq[Txo],
    lockPredicateFrom: Lock.Predicate,
    groupPolicy:       GroupPolicy,
    quantityToMint:    Long,
    mintedAddress:     LockAddress,
    changeAddress:     LockAddress,
    fee:               Long
  ): F[Either[BuilderError, IoTransaction]] = ???

  override def buildSeriesMintingTransaction(
    txos:              Seq[Txo],
    lockPredicateFrom: Lock.Predicate,
    seriesPolicy:      SeriesPolicy,
    quantityToMint:    Long,
    mintedAddress:     LockAddress,
    changeAddress:     LockAddress,
    fee:               Long
  ): F[Either[BuilderError, IoTransaction]] = ???

  override def buildAssetMintingTransaction(
    mintingStatement:       AssetMintingStatement,
    txos:                   Seq[Txo],
    locks:                  Map[LockAddress, Lock.Predicate],
    fee:                    Long,
    mintedAssetLockAddress: LockAddress,
    changeAddress:          LockAddress,
    ephemeralMetadata:      Option[Struct],
    commitment:             Option[ByteString]
  ): F[Either[BuilderError, IoTransaction]] = ???

  override def unprovenAttestation(
    lockPredicate: Lock.Predicate
  ): F[Attestation] = ???

  override def lockAddress(lock: Lock): F[LockAddress] = ???

  override def lvlOutput(
    predicate: Lock.Predicate,
    amount:    Int128
  ): F[UnspentTransactionOutput] = ???

  override def lvlOutput(
    lockAddress: LockAddress,
    amount:      Int128
  ): F[UnspentTransactionOutput] = ???

  override def datum(): F[Datum.IoTransaction] = ???

  override def buildSimpleLvlTransaction(
    lvlTxos:                Seq[Txo],
    lockPredicateFrom:      Lock.Predicate,
    lockPredicateForChange: Lock.Predicate,
    recipientLockAddress:   LockAddress,
    amount:                 Long
  ): F[IoTransaction] = ???

}
