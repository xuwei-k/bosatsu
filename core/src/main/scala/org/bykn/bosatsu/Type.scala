package org.bykn.bosatsu

import cats.data.NonEmptyList
import cats.Order
import cats.implicits._

sealed abstract class Type {
  import Type._

  def varsIn: List[Type.Var] =
    this match {
      case v@Var(_) => v :: Nil
      case Arrow(from, to) =>
        (from.varsIn ::: to.varsIn).distinct
      case TypeApply(hk, arg) =>
        (hk.varsIn ::: arg.varsIn).distinct
      case _ =>
        Nil
    }
}
object Type {
  case class Arrow(from: Type, to: Type) extends Type
  case class Declared(packageName: PackageName, name: String) extends Type
  case class TypeApply(hk: Type, arg: Type) extends Type
  //case class TypeLambda(param: String, in: Type) extends Type
  case class Var(name: String) extends Type

  private def predef(t: String): Type =
    Declared(PackageName(NonEmptyList.of("Bosatsu", "Predef")), t)

  val intT: Type = predef("Int")
  val boolT: Type = predef("Bool")
  val strT: Type = predef("String")

  def transformDeclared(in: Type)(fn: Declared => Declared): Type =
    in match {
      case Arrow(a, b) =>
        Arrow(transformDeclared(a)(fn), transformDeclared(b)(fn))
      case TypeApply(t, a) =>
        TypeApply(transformDeclared(t)(fn), transformDeclared(a)(fn))
      case d@Declared(_, _) => fn(d)
      case v@Var(_) => v
    }

  @annotation.tailrec
  final def rootDeclared(t: Type): Option[Declared] =
    t match {
      case decl@Declared(_, _) => Some(decl)
      case TypeApply(left, _) => rootDeclared(left)
      case _ => None
    }

  implicit val ordType: Order[Type] =
    new Order[Type] {
      def compare(a: Type, b: Type): Int =
        (a, b) match {
          case (Arrow(aa, ab), Arrow(ba, bb)) =>
            val c = compare(aa, ba)
            if (c == 0) compare(ab, bb)
            else c
          case (Arrow(_, _), _) => -1 // Arrow befor all other
          case (Declared(pa, na), Declared(pb, nb)) =>
            val c = Order[PackageName].compare(pa, pb)
            if (c == 0) na.compareTo(nb)
            else c
          case (Declared(_, _), Arrow(_, _)) => 1 // we are after Arrow
          case (Declared(_, _), _) => -1 // before everything else
          case (TypeApply(aa, ab), TypeApply(ba, bb)) =>
            val c = compare(aa, ba)
            if (c == 0) compare(ab, bb)
            else c
          //case (TypeApply(_, _), TypeLambda(_, _)) => -1
          case (TypeApply(_, _), Var(_)) => -1
          case (TypeApply(_, _), _) => 1
          // case (TypeLambda(pa, ta), TypeLambda(pb, tb)) =>
          //   val c = pa.compareTo(pb)
          //   if (c == 0) compare(ta, tb)
          //   else c
          // case (TypeLambda(_, _), Var(_)) => -1
          // case (TypeLambda(_, _), _) => 1
          case (Var(na), Var(nb)) => na.compareTo(nb)
          case (Var(_), _) => 1
        }
    }
}


case class Scheme(vars: List[String], result: Type) {
  import Type._

  def normalized: Scheme = {

    @annotation.tailrec
    def inOrd(t: Type, toVisit: List[Type], acc: List[String]): List[String] =
      t match {
        case Arrow(a, b) => inOrd(a, b :: toVisit, acc)
        case Declared(_, _) =>
          toVisit match {
            case Nil => acc.reverse
            case h :: tail => inOrd(h, tail, acc)
          }
        case TypeApply(hk, arg) => inOrd(hk, arg :: toVisit, acc)
        //case TypeLambda(_, t) => inOrd(t, toVisit, acc)
        case Var(v) => v :: Nil
          toVisit match {
            case Nil => (v :: acc).reverse
            case h :: tail => inOrd(h, tail, v :: acc)
          }
      }

    def iToC(i: Int): Char = ('a'.toInt + i).toChar
    @annotation.tailrec
    def idxToLetter(i: Int, acc: List[Char] = Nil): String =
      if (i < 26 && 0 <= i) (iToC(i) :: acc).mkString
      else {
        val rem = i % 26
        val next = i / 26
        idxToLetter(next, iToC(rem) :: acc)
      }

    val inOrdDistinct = inOrd(result, Nil, Nil).distinct
    val mapping: List[(String, String)] =
      inOrdDistinct.zipWithIndex.map { case (i, idx) =>
        i -> idxToLetter(idx)
      }

    val mappingMap = mapping.toMap

    def norm(t: Type): Type =
      t match {
        case Arrow(a, b) => Arrow(norm(a), norm(b))
        case d@Declared(_, _) => d
        case TypeApply(hk, arg) => TypeApply(norm(hk), norm(arg))
        //case TypeLambda(v, t) => TypeLambda(v, norm(t))
        case Var(v) => Var(mappingMap(v))
      }

    Scheme(mapping.map(_._2), norm(result))
  }
}

object Scheme {
  def fromType(t: Type): Scheme = Scheme(Nil, t)

  def typeConstructor(t: Type): Scheme =
    Scheme(t.varsIn.map(_.name), t).normalized
}

case class ConstructorName(asString: String)

object ConstructorName {
  implicit val orderCN: Order[ConstructorName] = Order[String].contramap[ConstructorName](_.asString)
}

case class ParamName(asString: String)
case class TypeName(asString: String)



