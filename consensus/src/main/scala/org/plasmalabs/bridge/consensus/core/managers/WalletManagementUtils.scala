package org.plasmalabs.bridge.consensus.core.managers

import cats.effect.kernel.Sync
import org.plasmalabs.crypto.encryption.VaultStore
import org.plasmalabs.sdk.dataApi.WalletKeyApiAlgebra
import org.plasmalabs.sdk.wallet.WalletApi
import quivr.models.KeyPair

class WalletManagementUtils[F[_]: Sync](
  walletApi: WalletApi[F],
  dataApi:   WalletKeyApiAlgebra[F]
) {

  def loadKeys(keyfile: String, password: String) = {
    import cats.implicits._
    for {
      wallet <- readInputFile(keyfile)
      keyPair <-
        walletApi
          .extractMainKey(wallet, password.getBytes())
          .flatMap(
            _.fold(
              _ match {
                case WalletApi.FailedToDecodeWallet(_) =>
                  Sync[F].raiseError[KeyPair](
                    new Throwable(
                      "Failed to decode wallet: check that the password is correct"
                    )
                  )
                case _ =>
                  Sync[F].raiseError[KeyPair] {
                    new Throwable(
                      "There was a problem decoding the wallet (check that the password is correct)"
                    )
                  }
              },
              Sync[F].point(_)
            )
          )
    } yield keyPair
  }

  def readInputFile(
    inputFile: String
  ): F[VaultStore[F]] = {
    import cats.implicits._
    dataApi
      .getMainKeyVaultStore(inputFile)
      .flatMap(
        _.fold(
          x =>
            Sync[F].raiseError[VaultStore[F]](
              new Throwable("Error reading input file: " + x)
            ),
          Sync[F].point(_)
        )
      )

  }

}
