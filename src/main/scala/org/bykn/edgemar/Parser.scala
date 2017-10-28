package org.bykn.edgemar

import cats.data.NonEmptyList
import fastparse.all._

object Parser {
  def inRange(lower: Char, c: Char, upper: Char): Boolean =
    (lower <= c) && (c <= upper)

  def isNum(c: Char): Boolean =
    inRange('0', c, '9')

  def isLower(c: Char): Boolean =
    inRange('a', c, 'z')

  def isUpper(c: Char): Boolean =
    inRange('A', c, 'Z')

  def isSpace(c: Char): Boolean =
    (c == ' ') | (c == '\t')

  val spaces: P[Unit] = P(CharsWhile(isSpace _))
  val maybeSpace: P[Unit] = spaces.?

  val lowerIdent: P[String] =
    P(CharIn('a' to 'z').! ~ CharsWhile(c => isNum(c) || isUpper(c) || isLower(c)).?.!)
      .map { case (a, b) => a + b }

  val upperIdent: P[String] =
    P(CharIn('A' to 'Z').! ~ CharsWhile(c => isNum(c) || isUpper(c) || isLower(c)).?.!)
      .map { case (a, b) => a + b }

  def tokenP[T](s: String, t: T): P[T] = P(s).map(_ => t)

  def integerString: P[String] = {
    val nonZero: P[String] = P(CharIn('1' to '9').! ~ (CharsWhile(isNum _).!.?))
      .map {
        case (f, None) => f
        case (f, Some(r)) => f + r
      }

    val positive: P[String] = tokenP("0", "0") | nonZero
    P(CharIn("+-").!.? ~ positive)
      .map {
        case (None, rest) => rest
        case (Some(s), rest) => s + rest
      }
  }

  def indented[T](fn: String => P[T]): P[T] =
    spaces.!.flatMap { extra => P(fn(extra)) }

  implicit class Combinators[T](val item: P[T]) extends AnyVal {
    def list: P[List[T]] = listN(0)

    def listN(min: Int): P[List[T]] =
      if (min == 0) {
        nonEmptyList.?
          .map {
            case None => Nil
            case Some(nel) => nel.toList
          }
      } else nonEmptyListOf(min).map(_.toList)

    def nonEmptyList: P[NonEmptyList[T]] = nonEmptyListOf(1)

    def nonEmptyListOf(min: Int): P[NonEmptyList[T]] = {
      require(min >= 1, s"min is too small: $min")
      val many = P(("," ~ maybeSpace ~ item ~ maybeSpace).rep())
      P(item ~ maybeSpace ~ many.? ~ (",".?))
        .map {
          case (h, None) => NonEmptyList(h, Nil)
          case (h, Some(nel)) => NonEmptyList(h, nel.toList)
        }
    }

    def trailingSpace: P[T] =
      P(item ~ maybeSpace)

    def prefixedBy(indent: String): P[T] =
      P(indent ~ item)

    def wrappedSpace(left: P[Unit], right: P[Unit]): P[T] =
      P(left ~ maybeSpace ~ item ~ maybeSpace ~ right)

    def parens: P[T] =
      wrappedSpace("(", ")")
  }


  val operatorParse: P[Operator] =
    tokenP("+", Operator.Plus) |
      tokenP("-", Operator.Sub) |
      tokenP("*", Operator.Mul) |
      tokenP("==", Operator.Eql)


  val toEOL: P[Unit] = P(maybeSpace ~ "\n")

  val typeScheme: P[Scheme] = {
    def prim(s: String) = P(s).map(_ => Scheme(Nil, Type.Primitive(s)))

    val item = prim("Int") | prim("Bool")
    P(item ~ (spaces ~/ "->" ~ spaces ~ typeScheme).?).map {
      case (t, None) => t
      case (a, Some(b)) => Scheme(Nil, Type.Arrow(a.result, b.result)) // TODO this is ignoring type variables for now
    }
  }

  val intP: P[Int] = P(CharsWhile(isNum _).!).map(_.toInt)
  val boolP: P[Boolean] =
    P("True").map(_ => true) | P("False").map(_ => false)
}
