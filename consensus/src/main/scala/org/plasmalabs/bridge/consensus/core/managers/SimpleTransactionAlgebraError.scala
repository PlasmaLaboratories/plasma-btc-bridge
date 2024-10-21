package xyz.stratalab.bridge.consensus.core.managers

sealed trait SimpleTransactionAlgebraError extends Throwable {

  def description: String

}

case class CannotInitializeProtobuf(description: String) extends SimpleTransactionAlgebraError

case class InvalidProtobufFile(description: String) extends SimpleTransactionAlgebraError

case class CannotSerializeProtobufFile(description: String) extends SimpleTransactionAlgebraError

case class NetworkProblem(description: String) extends SimpleTransactionAlgebraError

case class UnexpectedError(description: String) extends SimpleTransactionAlgebraError

case class CreateTxError(description: String) extends SimpleTransactionAlgebraError

case class ValidateTxErrpr(description: String) extends SimpleTransactionAlgebraError
