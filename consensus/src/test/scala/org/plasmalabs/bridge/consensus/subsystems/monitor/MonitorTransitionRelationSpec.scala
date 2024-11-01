package org.plasmalabs.bridge.consensus.monitor

import cats.effect.IO
import cats.effect.kernel.Async
import munit.CatsEffectSuite
import org.bitcoins.core.protocol.Bech32Address
import org.plasmalabs.bridge.consensus.core.controllers.SharedData
import org.plasmalabs.bridge.consensus.shared.AssetToken
import org.plasmalabs.bridge.consensus.subsystems.monitor.{
  BTCFundsDeposited,
  BTCFundsWithdrawn,
  BlockchainEvent,
  EndTransition,
  FSMTransitionTo,
  MConfirmingBTCClaim,
  MConfirmingBTCDeposit,
  MConfirmingRedemption,
  MConfirmingTBTCMint,
  MMintingTBTC,
  MWaitingForBTCDeposit,
  MWaitingForClaim,
  MWaitingForRedemption,
  MonitorTransitionRelation,
  NewBTCBlock,
  NewPlasmaBlock,
  NodeFundsDeposited,
  NodeFundsWithdrawn,
  PeginStateMachineState
}
import org.plasmalabs.sdk.syntax._
import org.plasmalabs.sdk.utils.Encoding

import scala.annotation.nowarn

class MonitorTransitionRelationSpec extends CatsEffectSuite with SharedData {

  val escrowAddress =
    "bcrt1qsc9qvqvlswpzlvf4t80g05l2la2cykazmdcur45st5g339vw6aps47j7sw"

  val escrowAddressPubkey =
    Bech32Address.fromString(escrowAddress).scriptPubKey.asmHex

  val escrowAddressOther =
    "bcrt1q0xlvz3kxy9vyx4ylghajrvwuyqkspn7pdsch20jn5wjjkhcensus805640"

  val redeemAddress =
    "ptetP7jshHVptQYvKZfMjruCBvWENnp4KbUT7t83c7pk3Y5uuo9GwjxnzERW"

  val redeemAddressOther =
    "ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p"

  val claimAddress =
    "bcrt1q0xlvz3kxy9vyx4ylghajrvwuyqkspn7pdsch20jn5wjjkhcensus805640"

  val claimAddressPubkey =
    Bech32Address.fromString(claimAddress).scriptPubKey.asmHex

  @nowarn // just dummy function
  def transitionToEffect[F[_]: Async](
    currentState:    PeginStateMachineState,
    blockchainEvent: BlockchainEvent
  ) = Async[F].unit

  import org.bitcoins.core.currency.SatoshisLong

  test(
    "PeginTransitionRelation should go from MWaitingForBTCDeposit to WaitingForEscrowBTCConfirmation on deposited funds"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForBTCDeposit(
            1,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MConfirmingBTCDeposit]
    )
  }

  test(
    "PeginTransitionRelation should not transition from MWaitingForBTCDeposit when the funds are not for the escrow address"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForBTCDeposit(
            1,
            1,
            "",
            escrowAddressOther,
            redeemAddress,
            claimAddress
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // MWaitingForBTCDeposit -> EndTransition when height difference bigger than expiration time
  test(
    "PeginTransitionRelation should transition from MWaitingForBTCDeposit to EndTransition when the height difference is bigger than expiration time"
  ) {
    assert(
      (MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForBTCDeposit(
            1,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress
          ),
          NewBTCBlock(102)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn)
    )
  }

  // MWaitingForBTCDeposit not transition on Node events
  test(
    "PeginTransitionRelation should not transition from MWaitingForBTCDeposit on Node events"
  ) {
    import org.plasmalabs.sdk.syntax._
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForBTCDeposit(
            1,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress
          ),
          NodeFundsDeposited(
            currentPlasmaBlockHeight = 0L, // Assuming a placeholder value for the missing argument
            address = redeemAddress,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForBTCDeposit(
            1,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress
          ),
          NodeFundsWithdrawn(
            1L,
            "nodeTxId",
            0,
            "plasma-secret",
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  test(
    "PeginTransitionRelation should transition from WaitingForRedemption to MConfirmingRedemption"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0,
            utxoTxId = "nodeTxId",
            utxoIndex = 0, // Added missing utxoIndex parameter
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          NodeFundsWithdrawn(
            1L,
            "nodeTxId",
            0,
            "plasma-secret",
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MConfirmingRedemption]
    )
  }

  test(
    "PeginTransitionRelation should transition from WaitingForRedemption to EndTransition when the height difference is bigger than expiration time"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0,
            utxoTxId = "nodeTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          NewPlasmaBlock(2002)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn
    )
  }

  test(
    "PeginTransitionRelation should NOT transition from WaitingForRedemption to NodeFundsWithdrawn if guard fails"
  ) {
    import org.plasmalabs.sdk.syntax._
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0,
            utxoTxId = "nodeTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          NodeFundsWithdrawn(
            1L,
            "nodeTxIdDifferent",
            0,
            "plasma-secret",
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0,
            utxoTxId = "nodeTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          NodeFundsWithdrawn(
            1L,
            "nodeTxId",
            1,
            "plasma-secret",
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // WaitingForRedemption not transition of BTC events
  test(
    "PeginTransitionRelation should not transition from WaitingForRedemption on BTC events"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0,
            utxoTxId = "nodeTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0,
            utxoTxId = "nodeTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          BTCFundsWithdrawn("txId", 0)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  test(
    "PeginTransitionRelation should transition from WaitingForClaim to WaitingForClaimBTCConfirmation"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForClaim(
            someStartBtcBlockHeight = None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId = "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          BTCFundsDeposited(2, claimAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MConfirmingBTCClaim]
      &&
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForClaim(
            someStartBtcBlockHeight = None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId = "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // WaitingForClaimBTCConfirmation -> EndTransition when timeout
  test(
    "PeginTransitionRelation should transition from WaitingForClaimBTCConfirmation to EndTransition when timeout"
  ) {
    assert(
      (MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MConfirmingBTCClaim(
            1,
            "secret",
            1,
            "btcTxId",
            0,
            "scriptAsm",
            AssetToken("groupId", "seriesId", 100L),
            claimAddress
          ),
          NewBTCBlock(8)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn) &&
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MConfirmingBTCClaim(
            1,
            "secret",
            1,
            "btcTxId",
            0,
            "scriptAsm",
            AssetToken("groupId", "seriesId", 100L),
            claimAddress
          ),
          NewBTCBlock(7)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  test(
    "PeginTransitionRelation should not transition from WaitingForClaim to EndTransition when the address is different"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForClaim(
            someStartBtcBlockHeight = None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId = "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // WaitingForClaim not transition on Node events
  test(
    "PeginTransitionRelation should not transition from WaitingForClaim on Node events"
  ) {
    import org.plasmalabs.sdk.syntax._
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForClaim(
            someStartBtcBlockHeight = None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId = "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          NodeFundsDeposited(
            currentPlasmaBlockHeight = 0L, // Assuming a missing parameter needs to be added
            address = redeemAddress,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ) // Assuming AssetToken is a valid NodeCurrencyUnit
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MWaitingForClaim(
            someStartBtcBlockHeight = None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId = "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          NodeFundsWithdrawn(
            1L,
            "nodeTxId",
            0,
            "plasma-secret",
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // MintingTBTC -> EndTransition when timeout
  test(
    "PeginTransitionRelation should transition from MintingTBTC to EndTransition when timeout"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MMintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.satoshis
          ),
          NewBTCBlock(102)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn
    )
  }

  // MintingTBTC -> WaitingForRedemption
  test(
    "PeginTransitionRelation should transition from MintingTBTC to MintingTBTCConfirmation"
  ) {

    import org.plasmalabs.sdk.syntax._
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MMintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.satoshis
          ),
          NodeFundsDeposited(
            currentPlasmaBlockHeight = 0L, // Assuming a missing parameter needs to be added
            address = redeemAddress,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken(
              Encoding.encodeToBase58(groupId.value.toByteArray),
              Encoding.encodeToBase58(seriesId.value.toByteArray),
              100L
            ) // Assuming AssetToken is a valid NodeCurrencyUnit
          )
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MConfirmingTBTCMint]
    )
  }

  // MintingTBTC -> WaitingForRedemption not transition
  test(
    "PeginTransitionRelation should not transition from MintingTBTC to WaitingForRedemption"
  ) {
    import org.plasmalabs.sdk.syntax._
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MMintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.satoshis
          ),
          NodeFundsDeposited(
            currentPlasmaBlockHeight = 0L, // Assuming a missing parameter needs to be added
            address = redeemAddressOther,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ) // Assuming AssetToken is a valid NodeCurrencyUnit
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // MintingTBTC not transition on BTC events
  test(
    "PeginTransitionRelation should not transition from MintingTBTC on BTC events"
  ) {
    assert(
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MMintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.sats
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
      MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MMintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.satoshis
          ),
          BTCFundsWithdrawn("txId", 0)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // WaitingForEscrowBTCConfirmation -> MintingTBTC
  test(
    "PeginTransitionRelation should transition from WaitingForEscrowBTCConfirmation to MintingTBTC"
  ) {
    println(
      org.plasmalabs.bridge.consensus.subsystems.monitor.MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MConfirmingBTCDeposit(
            1,
            1,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.satoshis
          ),
          NewBTCBlock(7)
        )(transitionToEffect[IO](_, _))
    )
    assert(
      org.plasmalabs.bridge.consensus.subsystems.monitor.MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MConfirmingBTCDeposit(
            1,
            1,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.sats
          ),
          NewBTCBlock(8)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MMintingTBTC]
    )
  }

  // WaitingForEscrowBTCConfirmation -> MWaitingForBTCDeposit
  test(
    "PeginTransitionRelation should transition from WaitingForEscrowBTCConfirmation to MWaitingForBTCDeposit on reorg"
  ) {
    assert(
      org.plasmalabs.bridge.consensus.subsystems.monitor.MonitorTransitionRelation
        .handleBlockchainEvent[IO](
          MConfirmingBTCDeposit(
            1,
            8,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100.satoshis
          ),
          NewBTCBlock(8)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MWaitingForBTCDeposit]
    )
  }

}
