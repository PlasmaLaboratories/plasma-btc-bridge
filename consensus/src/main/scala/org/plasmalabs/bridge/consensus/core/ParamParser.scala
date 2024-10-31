package org.plasmalabs.bridge.consensus.core

import com.google.protobuf.ByteString
import org.bitcoins.core.currency.{CurrencyUnit, SatoshisLong}
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import org.plasmalabs.sdk.utils.Encoding

import scala.util.{Failure, Success, Try}

object ParamParser {

  implicit val networkRead: scopt.Read[BitcoinNetworkIdentifiers] =
    scopt.Read
      .reads(BitcoinNetworkIdentifiers.fromString(_))
      .map(_ match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException(
            "Invalid network. Possible values: mainnet, testnet, regtest"
          )
      })

  implicit val toplNetworkRead: scopt.Read[PlasmaNetworkIdentifiers] =
    scopt.Read
      .reads(PlasmaNetworkIdentifiers.fromString(_))
      .map(_ match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException(
            "Invalid network. Possible values: mainnet, testnet, private"
          )
      })

  implicit val currencyUnit: scopt.Read[CurrencyUnit] =
    scopt.Read
      .reads(x => Try(x.toLong.satoshis))
      .map(_ match {
        case Success(v) => v
        case Failure(_) =>
          throw new IllegalArgumentException(
            "Could not conver value to satoshi"
          )
      })

  implicit val groupIdRead: scopt.Read[GroupId] =
    scopt.Read.reads { x =>
      val array = Encoding.decodeFromHex(x).toOption match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException("Invalid group id")
      }
      GroupId(ByteString.copyFrom(array))
    }

  implicit val seriesIdRead: scopt.Read[SeriesId] =
    scopt.Read.reads { x =>
      val array = Encoding.decodeFromHex(x).toOption match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException("Invalid series id")
      }
      SeriesId(ByteString.copyFrom(array))
    }

}
