package org.bykn.bosatsu

import cats.Eval
import cats.data.{Validated, ValidatedNel, NonEmptyList}
import cats.implicits._
import com.monovore.decline._
import java.nio.file.Path
import org.typelevel.paiges.Doc
import scala.util.Try

object Foo {
  def times(i: java.lang.Integer): java.lang.Integer =
    java.lang.Integer.valueOf(i.intValue + 42)
}

object Std {
  //fold = ffi scala org.bykn.bosatsu.Std.fold List[a] -> b -> (b -> a -> b) -> b
  @annotation.tailrec
  final def fold(list: Any, bv: Any, fn: Any): Any = {
    list match {
      case (0, _) =>
        // Empty
        bv
      case (1, head :: tail :: Nil) =>
        val fnT = fn.asInstanceOf[Fn[Any, Fn[Any, Any]]]
        fold(tail, fnT(bv)(head), fn)
      case _ => sys.error(s"unexpected: $list")
    }
  }

  def unitValue: AnyRef = (0, Nil)

  def print(i: Any): Any =
    Eval.always { println(i.toString); unitValue }

  def flatMap(act: Any, fn: Any): Any =
    act.asInstanceOf[Eval[Any]].flatMap { v =>
      fn.asInstanceOf[Fn[Any, Eval[Any]]](v)
    }
  def mapAction(act: Any, fn: Any): Any =
    act.asInstanceOf[Eval[Any]].map { v =>
      fn.asInstanceOf[Fn[Any, Any]](v)
    }

  def toAction(a: Any): Any = Eval.now(a)
  def runAction(a: Any): Any = a.asInstanceOf[Eval[Any]].value
}

sealed abstract class MainResult[+A] {
  def map[B](fn: A => B): MainResult[B]
  def flatMap[B](fn: A => MainResult[B]): MainResult[B]
}
object MainResult {
  case class Error(code: Int, errLines: List[String]) extends MainResult[Nothing] {
    def map[B](fn: Nothing => B) = this
    def flatMap[B](fn: Nothing => MainResult[B]) = this
  }
  case class Success[A](result: A) extends MainResult[A] {
    def map[B](fn: A => B) = Success(fn(result))
    def flatMap[B](fn: A => MainResult[B]) = fn(result)
  }

  def fromTry[A](t: Try[A]): MainResult[A] =
    t match {
      case scala.util.Success(a) =>
        MainResult.Success(a)
      case scala.util.Failure(err) =>
        MainResult.Error(1, List(err.toString, err.getMessage))
    }

  def product[A, B](a: MainResult[A], b: MainResult[B])(errs: (Int, Int) => Int): MainResult[(A, B)] =
    (a, b) match {
      case (Error(a, la), Error(b, lb)) => Error(errs(a, b), la ::: lb)
      case (e@Error(_, _), _) => e
      case (_, e@Error(_, _)) => e
      case (Success(a), Success(b)) => Success((a, b))
    }
}

sealed abstract class MainCommand {
  def run(): MainResult[List[String]]
}
object MainCommand {

  def typeCheck(inputs: NonEmptyList[Path], externals: List[Path]): MainResult[(Externals, PackageMap.Inferred)] = {
    val ins = parseInputs(inputs)
    val exts = readExternals(externals)

    toResult(ins.product(exts))
      .flatMap { case (packs, exts) =>
        val (dups, resPacks) = PackageMap.resolveThenInfer(Predef.withPredefA(("predef", LocationMap("")), packs.toList))
        val checkD = checkDuplicatePackages(dups)(_._1)
        val map = packs.map { case ((path, lm), pack) => (pack.name, (lm, path)) }.toList.toMap
        val checkPacks = fromPackageError(map, resPacks)
        MainResult.product(checkD, checkPacks)(_ max _)
          .map { case (_, p) => (exts, p) }
      }
  }

  case class Evaluate(inputs: NonEmptyList[Path], externals: List[Path], mainPackage: PackageName) extends MainCommand {
    def run() =
      typeCheck(inputs, externals).flatMap { case (ext, packs) =>
        val ev = Evaluation(packs, Predef.jvmExternals ++ ext)
        ev.evaluateLast(mainPackage) match {
          case None => MainResult.Error(1, List("found no main expression"))
          case Some((eval, scheme)) =>
            val res = eval.value
            MainResult.Success(List(s"$res: $scheme"))
        }
      }
  }
  case class ToJson(inputs: NonEmptyList[Path], externals: List[Path], signatures: List[Path], mainPackage: PackageName, output: Path) extends MainCommand {
    def run() =
      typeCheck(inputs, externals).flatMap { case (ext, packs) =>
        val ev = Evaluation(packs, Predef.jvmExternals ++ ext)
        ev.evaluateLast(mainPackage) match {
          case None => MainResult.Error(1, List("found no main expression"))
          case Some((eval, scheme)) =>
            val res = eval.value
            ev.toJson(res, scheme) match {
              case None =>
                MainResult.Error(1, List(s"cannot convert type to Json: $scheme"))
              case Some(j) =>
                MainResult.fromTry(CodeGen.writeDoc(output, j.toDoc))
                  .map { _ => Nil }
            }
        }
      }
  }
  case class TypeCheck(inputs: NonEmptyList[Path], signatures: List[Path], output: Path) extends MainCommand {
    def run() =
      typeCheck(inputs, Nil).flatMap { case (_, packs) =>
        MainResult.fromTry(CodeGen.writeDoc(output, Doc.text(s"checked ${packs.toMap.size} packages")))
          .map(_ => Nil)
      }
  }
  case class Compile(inputs: NonEmptyList[Path], externals: List[Path], compileRoot: Path) extends MainCommand {
    def run() =
      typeCheck(inputs, externals).flatMap { case (ext, packs) =>
        MainResult.fromTry(CodeGen.write(compileRoot, packs, Predef.jvmExternals ++ ext))
          .map { _ => List(s"wrote ${packs.toMap.size} packages") }
      }
  }

  def readExternals(epaths: List[Path]): ValidatedNel[Parser.Error, Externals] =
    epaths match {
      case Nil => Validated.valid(Externals.empty)
      case epaths =>
        epaths.traverse(Parser.parseFile(Externals.parser, _))
          .map(_.toList.map(_._2).reduce(_ ++ _))
    }

  def parseInputs(paths: NonEmptyList[Path]): ValidatedNel[Parser.Error, NonEmptyList[((String, LocationMap), Package.Parsed)]] =
    paths.traverse { path =>
      Parser.parseFile(Package.parser, path).map { case (lm, parsed) =>
        ((path.toString, lm), parsed)
      }
    }

  def toResult[A](v: ValidatedNel[Parser.Error, A]): MainResult[A] =
    v match {
      case Validated.Valid(a) => MainResult.Success(a)
      case Validated.Invalid(errs) =>
        val msgs = errs.toList.flatMap {
          case Parser.Error.PartialParse(_, pos, map, Some(path)) =>
            // we should never be partial here
            val (r, c) = map.toLineCol(pos).get
            val ctx = map.showContext(pos).get
            List(s"failed to parse completely $path at line ${r + 1}, column ${c + 1}",
                ctx.toString)
          case Parser.Error.ParseFailure(pos, map, Some(path)) =>
            // we should never be partial here
            val (r, c) = map.toLineCol(pos).get
            val ctx = map.showContext(pos).get
            List(s"failed to parse $path at line ${r + 1}, column ${c + 1}",
                ctx.toString)
          case Parser.Error.FileError(path, err) =>
            List(s"failed to parse $path",
                err.getMessage,
                err.getClass.toString)
          case other =>
            List(s"unexpected error $other")
        }
      MainResult.Error(1, msgs)
    }

  def fromPackageError[A](sourceMap: Map[PackageName, (LocationMap, String)], v: ValidatedNel[PackageError, A]): MainResult[A] =
    v match {
      case Validated.Invalid(errs) => MainResult.Error(1, errs.map(_.message(sourceMap)).toList)
      case Validated.Valid(a) => MainResult.Success(a)
    }

  def checkDuplicatePackages[A](dups: Map[PackageName, ((A, Package.Parsed), NonEmptyList[(A, Package.Parsed)])])(fn: A => String): MainResult[Unit] =
    if (dups.isEmpty) MainResult.Success(())
    else
      MainResult.Error(1,
        dups.iterator.map { case (pname, ((src, _), nelist)) =>
          val dupsrcs = (fn(src) :: nelist.map { case (s, _) => fn(s) }.toList).sorted.mkString(", ")
          s"package ${pname.asString} duplicated in $dupsrcs"
        }.toList
      )

  val opts: Opts[MainCommand] = {
    implicit val argPack: Argument[PackageName] =
      new Argument[PackageName] {
        def defaultMetavar: String = "packageName"
        def read(string: String): ValidatedNel[String, PackageName] =
          PackageName.parse(string) match {
            case Some(pn) => Validated.valid(pn)
            case None => Validated.invalidNel(s"could not parse $string as a package name. Must be capitalized strings separated by /")
          }
      }

    def toList[A](o: Option[NonEmptyList[A]]): List[A] =
      o match {
        case None => Nil
        case Some(ne) => ne.toList
      }

    val ins = Opts.options[Path]("input", help = "input files")
    val extern = Opts.options[Path]("external", help = "input files").orNone.map(toList(_))
    val sigs = Opts.options[Path]("signature", help = "signature files").orNone.map(toList(_))

    val mainP = Opts.option[PackageName]("main", help = "main package to evaluate")
    val outputPath = Opts.option[Path]("output", help = "output path")
    val compileRoot = Opts.option[Path]("compile_root", help = "root directory to write java output")

    val evalOpt = (ins, extern, mainP).mapN(Evaluate(_, _, _))
    val toJsonOpt = (ins, extern, sigs, mainP, outputPath).mapN(ToJson(_, _, _, _, _))
    val typeCheckOpt = (ins, sigs, outputPath).mapN(TypeCheck(_, _, _))
    val compileOpt = (ins, extern, compileRoot).mapN(Compile(_, _, _))

    Opts.subcommand("eval", "evaluate an expression and print the output")(evalOpt)
      .orElse(Opts.subcommand("write-json", "evaluate a data expression into json")(toJsonOpt))
      .orElse(Opts.subcommand("type-check", "type check a set of packages")(typeCheckOpt))
      .orElse(Opts.subcommand("compile", "compile bosatsu to Java code")(compileOpt))
  }
}

object Main {
  def command: Command[MainCommand] =
    Command("bosatsu", "a total and functional programming language")(MainCommand.opts)

  def main(args: Array[String]): Unit =
    command.parse(args.toList) match {
      case Right(cmd) =>
        cmd.run() match {
          case MainResult.Error(code, errs) =>
            errs.foreach(System.err.println)
            System.exit(code)
          case MainResult.Success(lines) =>
            lines.foreach(println)
            System.exit(0)
        }
      case Left(help) =>
        System.err.println(help.toString)
        System.exit(1)
    }
}
