package im.actor.api

import scala.language.postfixOps
import treehugger.forest._, definitions._
import treehuggerDSL._

trait SerializationTrees extends TreeHelpers {
  private def CodedOutputStreamClass = valueCache("com.google.protobuf.CodedOutputStream")
  private def CodedOutputStream = REF(CodedOutputStreamClass)

  private def simpleWriter(writeFn: String, attrId: Int, attrName: String): Tree =
    simpleWriter(writeFn, attrId, REF(attrName))

  private def simpleWriter(writeFn: String, attrId: Int, attrValue: Tree): Tree = {
    REF("out") DOT(writeFn) APPLY(LIT(attrId), attrValue)
  }

  private def writer(id: Int, name: String, typ: Types.AttributeType): Vector[Tree] = {
    typ match {
      case Types.Int32 => Vector(simpleWriter("writeInt32", id, name))
      case Types.Int64 => Vector(simpleWriter("writeInt64", id, name))
      case Types.Bool => Vector(simpleWriter("writeBool", id, name))
      case Types.Double => Vector(simpleWriter("writeDouble", id, name))
      case Types.String => Vector(simpleWriter("writeString", id, name))
      case Types.Bytes => Vector(simpleWriter("writeByteArray", id, name))
      case Types.Enum(_) =>
        Vector(simpleWriter("writeEnum", id, REF(name) DOT("id")))
      case Types.Opt(optAttrType) =>
        Vector(
          REF(name) FOREACH LAMBDA(PARAM("x")) ==> BLOCK(
            writer(id, "x", optAttrType)
          )
        )
      case Types.List(listAttrType) =>
        Vector(
          REF(name) FOREACH LAMBDA(PARAM("x")) ==> BLOCK(
            writer(id, "x", listAttrType)
          )
        )
      case Types.Struct(structName) =>
        Vector(
          REF("out") DOT("writeTag") APPLY(LIT(id), REF("com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED")),
          REF("out") DOT("writeRawVarint32") APPLY(REF(name) DOT("getSerializedSize")),
          REF(name) DOT("writeTo") APPLY(REF("out"))
        )
      case Types.Trait(traitName) =>
        val traitBaos = f"baos$traitName%s"
        val traitOut = f"out$traitName%s"

        Vector(
          VAL(traitBaos) := NEW(REF("java.io.ByteArrayOutputStream")),
          VAL(traitOut) := CodedOutputStreamClass DOT("newInstance") APPLY(REF(traitBaos)),
          REF(name) DOT("writeTo") APPLY(REF(traitOut)),
          REF(traitOut) DOT("flush") APPLY(),
          simpleWriter(
            "writeByteArray",
            id,
            REF(traitBaos) DOT("toByteArray")
          ),
          REF(traitBaos) DOT("close") APPLY()
        )
    }
  }

  private def simpleComputer(computeFn: String, attrId: Int, attrName: String): Tree =
    simpleComputer(computeFn, attrId, REF(attrName))

  private def simpleComputer(computeFn: String, attrId: Int, attrValue: Tree): Tree = {
    CodedOutputStream DOT(computeFn) APPLY(LIT(attrId), attrValue)
  }

  private def computer(id: Int, name: String, typ: Types.AttributeType): Vector[Tree] = {
    typ match {
      case Types.Int32 => Vector(simpleComputer("computeInt32Size", id, name))
      case Types.Int64 => Vector(simpleComputer("computeInt64Size", id, name))
      case Types.Bool => Vector(simpleComputer("computeBoolSize", id, name))
      case Types.Double => Vector(simpleComputer("computeDoubleSize", id, name))
      case Types.String => Vector(simpleComputer("computeStringSize", id, name))
      case Types.Bytes => Vector(simpleComputer("computeByteArraySize", id, REF(name)))
      case Types.Enum(_) => Vector(simpleComputer("computeEnumSize", id, REF(name) DOT("id")))
      case Types.Opt(optAttrType) =>
        Vector(
          REF(name) MAP LAMBDA(PARAM("x")) ==> BLOCK(
            computer(id, "x", optAttrType)
          ) POSTFIX("getOrElse") APPLY(LIT(0))
        )
      case Types.List(listAttrType) =>
        // TODO: optimize using view
        Vector(
          PAREN(REF(name) MAP LAMBDA(PARAM("x")) ==> BLOCK(
            computer(id, "x", listAttrType)
          )) DOT("foldLeft") APPLY(LIT(0)) APPLY(WILDCARD INT_+ WILDCARD)
        )
      case Types.Struct(_) | Types.Trait(_) =>
        Vector(BLOCK(
          VAL("size") := REF(name) DOT("getSerializedSize"),
          (CodedOutputStreamClass DOT("computeTagSize") APPLY(LIT(id))) INT_+
            (CodedOutputStreamClass DOT("computeRawVarint32Size") APPLY(REF("size"))) INT_+
            REF("size")
        ))
    }
  }

  protected def serializationTrees(packageName: String, name: String, attributes: Vector[Attribute]): Vector[Tree] = {
    val sortedAttributes = attributes.sortBy(_.id)

    val writers: Vector[Tree] = sortedAttributes map { attr =>
      writer(attr.id, attr.name, attr.typ)
    } flatten

    val sizeComputers: Vector[Tree] =
      if (sortedAttributes.length > 0) {
        sortedAttributes map { attr =>
          PAREN(
            computer(attr.id, attr.name, attr.typ)
          )
        }
      } else {
        Vector(LIT(0))
      }

    Vector(
      DEF("writeTo") withParams(PARAM("out", CodedOutputStreamClass)) := BLOCK(writers),
      DEF("getSerializedSize", IntClass) := BLOCK(INFIX_CHAIN("+", sizeComputers)),
      DEF("toByteArray", arrayType(ByteClass)) := BLOCK(
        VAL("res") := NEW(arrayType(ByteClass), REF("getSerializedSize")),
        VAL("out") := CodedOutputStreamClass DOT("newInstance") APPLY(REF("res")),
        REF("writeTo") APPLY(REF("out")),
        REF("out") DOT("checkNoSpaceLeft") APPLY(),
        REF("res")
      )
    )
  }

  protected def traitSerializationTrees(traitName: String, children: Vector[NamedItem]): Vector[Tree] = {
    if (children.length > 0) {
      Vector(
        DEF("writeTo", UnitClass) withParams(PARAM("out", CodedOutputStreamClass)),
        DEF("getSerializedSize", IntClass),
        DEF("toByteArray", arrayType(ByteClass))
      )
    } else {
      Vector.empty
    }
  }
}