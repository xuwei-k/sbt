package lmcoursier.definitions

import dataclass._

@data class Authentication(
  user: String,
  password: String,
  optional: Boolean = false,
  realmOpt: Option[String] = None,
  @since
  headers: Seq[(String,String)] = Nil
) {
  override def toString(): String =
    withPassword("****")
      .withHeaders(
        headers.map {
          case (k, v) => (k, "****")
        }
      )
      .productIterator
      .mkString("Authentication(", ", ", ")")
}

object Authentication {

  def apply(headers: Seq[(String, String)]): Authentication =
    Authentication("", "", optional = false, None, headers)
}
