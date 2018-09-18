package sample.blog.matr

import matryoshka._
import matryoshka.data.Mu
import matryoshka.implicits._

//import sample.blog.matr.Expressions
object Expressions {

  sealed abstract class Expr[A]
  final case class Num[A](value: Long) extends Expr[A]
  final case class Add[A](a: A, b: A) extends Expr[A]
  final case class Mul[A](l: A, r: A) extends Expr[A]


  //Fixed point style algebra
  sealed abstract class ExprF[A]
  final case class NumF[A](value: Long) extends ExprF[A]
  final case class AddF[A](a: A, b: A) extends ExprF[A]
  final case class MulF[A](l: A, r: A) extends ExprF[A]


  implicit val exprFunctor0 = new scalaz.Functor[Expr] {
    override def map[A, B](fa: Expr[A])(f: (A) => B) = fa match {
      case Num(value) => Num[B](value)
      case Mul(l, r) => Mul(f(l), f(r))
      case Add(l, r) => Add(f(l), f(r))
    }
  }

  implicit val exprFunctor = new scalaz.Functor[ExprF] {
    override def map[A, B](fa: ExprF[A])(f: (A) => B) = fa match {
      case NumF(value) => NumF[B](value)
      case MulF(l, r) => MulF(f(l), f(r))
      case AddF(l, r) => AddF(f(l), f(r))
    }
  }

  val evalExp: Algebra[ExprF, Long] = {
    case NumF(x) => x
    case MulF(x, y) =>
      val r = x * y
      println(s"$x * $y = $r")
      r
    case AddF(x, y) =>
      val r = x + y
      println(s"$x + $y = $r")
      r
  }

  val evalExp0: Expr[Long] => Long = {
    case Num(x) => x
    case Mul(x, y) =>
      val r = x * y
      println(s"$x * $y = $r")
      r
    case Add(x, y) =>
      val r = x + y
      println(s"$x + $y = $r")
      r
  }

  //( (2+3) * (3*4) )
  def someExpr[T](implicit T: Corecursive.Aux[T, ExprF]): T =
    MulF(
      AddF(NumF[T](2).embed, NumF[T](3).embed).embed,
      MulF(NumF[T](3).embed, NumF[T](4).embed).embed
    ).embed

  someExpr[Mu[ExprF]].cata(evalExp)

  /*
  sealed trait Tree[+A]
  case class Branch[+A](left: Tree[A], right: Tree[A]) extends Tree[A]
  case class Leaf[A](a: A) extends Tree[A]
  */
}
