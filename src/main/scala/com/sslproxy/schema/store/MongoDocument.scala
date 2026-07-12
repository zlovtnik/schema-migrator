package com.sslproxy.schema.store

import org.bson.Document

import scala.jdk.CollectionConverters.*

private[store] object MongoDocument:
  def requiredString(document: Document, field: String, documentType: String): String =
    optionalString(document, field)
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"$documentType document is missing required field '$field'"))

  def optionalString(document: Document, field: String): Option[String] =
    Option(document.getString(field)).filter(_.nonEmpty)

  def optionalRawString(document: Document, field: String): Option[String] =
    Option(document.getString(field))

  def optionalDocument(document: Document, field: String): Option[Document] =
    Option(document.get(field)).collect { case doc: Document => doc }

  def documentList(document: Document, field: String): List[Document] =
    Option(document.get(field)) match
      case Some(values: java.util.List[?]) =>
        values.asScala.toList.collect { case doc: Document => doc }
      case _ => Nil

  def intValue(document: Document, field: String, default: Int): Int =
    optionalInt(document, field).getOrElse(default)

  def optionalInt(document: Document, field: String): Option[Int] =
    Option(document.get(field)).collect { case number: java.lang.Number => number.intValue() }

  def optionalLong(document: Document, field: String): Option[Long] =
    Option(document.get(field)).collect { case number: java.lang.Number => number.longValue() }
