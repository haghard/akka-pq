package sample.blog

object Playground {

  trait Expr[A] { self: A ⇒
    def +[B <: AnyVal](b: B): A + b.type = new +(this, b)
    def +[B <: Expr[B]](b: B)(implicit ev: B <:< Expr[B]): A + B = new +(this, b)

    def *[B <: AnyVal](v: B): A mul v.type = mul(this, v)
    def *[B <: Expr[B]](b: B)(implicit ev: B <:< Expr[B]): A mul B = mul(this, b)
  }

  final case class +[A, B](a: A, b: B) extends Expr[A + B]
  final case class mul[A, B](a: A, b: B) extends Expr[A mul B]

  trait SchemaA extends Expr[SchemaA]
  object SchemaA extends SchemaA

  trait SchemaB extends Expr[SchemaB]
  object SchemaB extends SchemaB

  /*
  val aa = SchemaA.+(5).+(10)
  val bb = SchemaB.+("aa").+("bb")
  aa + bb
  */

  sealed trait Money[A <: Money[A]] {
    self ⇒
    def amount: BigDecimal

    def +(that: A): A
  }

  final case class Eur(override val amount: BigDecimal) extends Money[Eur] {
    def +(that: Eur): Eur = Eur(amount + that.amount)
  }

  final case class Rub(override val amount: BigDecimal) extends Money[Rub] {
    def +(that: Rub): Rub = Rub(amount + that.amount)
  }

  Eur(BigDecimal(3.67)) + Eur(BigDecimal(6.12))

  type |[+A, +B] = Union.Type[A, B]

  object Union {
    trait Tag extends Any
    type Base
    type Type[+A, +B] <: Base with Tag

    implicit def first[A]: A <:< Type[A, Nothing] =
      implicitly[A <:< A].asInstanceOf[A <:< Type[A, Nothing]]
    implicit def second[B]: B <:< Type[Nothing, B] =
      implicitly[B <:< B].asInstanceOf[B <:< Type[Nothing, B]]
  }

  case class A(a: Int)
  case class B(a: Int)

  type Or = A | B
  type All = A with B

  def sum(a: A, b: B): Or = ???
  def prod(a: A, b: B): All = ???

  //sum(A(1), B(1))

  //prod(A(1), B(1))

}
