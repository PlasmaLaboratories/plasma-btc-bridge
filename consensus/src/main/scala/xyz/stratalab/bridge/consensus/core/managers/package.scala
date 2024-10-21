package xyz.stratalab.bridge.consensus.core

import com.google.protobuf.struct.{ListValue, NullValue, Struct, Value}
import io.circe.Json
import xyz.stratalab.sdk.utils.Encoding

package object managers {

  def toStruct(json: Json): Value =
    json.fold[Value](
      jsonNull = Value(Value.Kind.NullValue(NullValue.NULL_VALUE)),
      jsonBoolean = b => Value(Value.Kind.BoolValue(b)),
      jsonNumber = n => Value(Value.Kind.NumberValue(n.toDouble)),
      jsonString = s => Value(Value.Kind.StringValue(s)),
      jsonArray = l => Value(Value.Kind.ListValue(ListValue(l.map(toStruct(_))))),
      jsonObject = jo =>
        Value(Value.Kind.StructValue(Struct(jo.toMap.map { case (k, v) =>
          k -> toStruct(v)
        })))
    )

  def templateFromSha(decodedHex: Array[Byte], min: Long, max: Long) =
    s"""
        {"threshold":1,"innerTemplates":[{"left":{"routine":"Sha256","digest":"${Encoding.encodeToBase58(
        decodedHex
      )}","type":"digest"},"right":{"chain":"header","min":$min,"max":$max,"type":"height"},"type":"and"}],"type":"predicate"}
        """
}
