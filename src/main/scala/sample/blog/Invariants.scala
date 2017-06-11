package sample.blog

import cats.Applicative

import scala.reflect.ClassTag

//runMain sample.blog.Invariants
object Invariants {

  implicit val setApplicative: Applicative[Set] = new Applicative[Set] {
    override def pure[A](x: A): Set[A] = Set(x)

    override def ap[A, B](ff: Set[(A) => B])(fa: Set[A]): Set[B] =
      for {a <- fa; f <- ff} yield f(a)
  }

  trait BooleanLogicCheck[In, State] {
    def name: String

    def apply(in: In, on: State): Boolean

    def errorMessage(in: In, state: State): String

    def emptyInputMessage = s"Empty input while checking $name"
  }

  sealed trait Precondition[F[_]] {
    def ignoreIfAbsent: Boolean
    def against[T, State](fa: F[T], state: State/*, ignoreNull: Boolean = false*/)
      (implicit P: BooleanLogicCheck[T, State], C: Catamorphism[F]): Either[String, F[T]]
  }

  class ExistedId extends BooleanLogicCheck[Long, Set[Long]] {
    override val name = "Precondition: KnownId"
    override def apply(in: Long, on: Set[Long]): Boolean = on.contains(in)
    override def errorMessage(in: Long, on: Set[Long]) = s"$in doesn't exists"
  }

  class UniqueName extends BooleanLogicCheck[String, Set[String]] {
    override val name = "Precondition: UniqueName"
    override def apply(in: String, state: Set[String]): Boolean = !state.contains(name)
    override def errorMessage(name: String, state: Set[String]) = s"$name is not unique"
  }


  class SuitableRoles extends BooleanLogicCheck[Set[Int], Set[Int]] {
    override val name = "Precondition: HasRoles"
    override def apply(ids: Set[Int], state: Set[Int]) = ids.filter(id => !state.contains(id)).isEmpty
    override def errorMessage(ids: Set[Int], state: Set[Int]) = s"User with roles [${ids.mkString(",")}] is not allowed to do this operation"
  }

  trait Catamorphism[F[_]] {
    def cata[A](opt: F[A])(ifNull: ⇒ Either[String, F[A]], ifNotNull: A ⇒ Either[String, F[A]]): Either[String, F[A]]
  }

  implicit object OptionalCatamorphism extends Catamorphism[Option] {
    override def cata[A](opt: Option[A])(
      ifNull: ⇒ Either[String, Option[A]],
      ifNotNull: (A) ⇒ Either[String, Option[A]]
    ): Either[String, Option[A]] = opt.fold(ifNull)(ifNotNull(_))
  }

  implicit object IdCatamorphism extends Catamorphism[cats.Id] {
    override def cata[A](opt: cats.Id[A])(
      ifNull: ⇒ Either[String, cats.Id[A]],
      ifNotNull: (A) ⇒ Either[String, cats.Id[A]]
    ): Either[String, cats.Id[A]] = if (opt == null) ifNull else ifNotNull(opt)
  }

  object Precondition {
    //: cats.Applicative
    private def create[T <: BooleanLogicCheck[_, _] : ClassTag, F[_]](ignore: Boolean) = {
      new Precondition[F] {
        override val ignoreIfAbsent = ignore
        override def against[T, State](in: F[T], state: State)
          (implicit B: BooleanLogicCheck[T, State], C: Catamorphism[F]): Either[String, F[T]] = {
          val success: Either[String, F[T]] = Right(in)
          println(B.name)
          if (ignoreIfAbsent)
            C.cata(in)(success, { input =>
              if (B(input, state)) success else Left(B.errorMessage(input, state))
            })
          else
            C.cata(in)(Left(B.emptyInputMessage), { input =>
              if (B(input, state)) success else Left(B.errorMessage(input, state))
            })
        }
      }
    }

    def nullable[T <: BooleanLogicCheck[_, _] : ClassTag, F[_]]: Precondition[F] =
      create(true)

    def apply[T <: BooleanLogicCheck[_, _] : ClassTag, F[_]]: Precondition[F] =
      create(false)
  }


  import shapeless._
  import ops.function._
  import syntax.std.function._

  /**
   *
   * P is a Product, that is a tuple or a case class
   * F is an unconstrained type parameter
   * L is an HList
   * R is an unconstrained type parameter
   *
   * Generic.Aux[P, L]; this is the built-in “predicate” that Shapeless provides to encode the relation between a product type P and an HList L.
   * It holds when it is possible to derive a Generic[P] instance that converts P into L
   *
   * FnToProduct.Aux[F, L => R]; is he built-in “predicate” that Shapeless provides to encode the relation that holds
   * when F can be converted into a function from HList L to return type R; it holds when it is possible
   * to derive an FnToProduct[F] instance called that converts F into L => R
   *
   * HLists can be seen as an alternative implementation of the concept of Tuple or more generally, of the concept of Product.
   *
   * Abstracting over arity
   */
  def product[P <: Product, F, L <: HList, R](p: P)(f: F)
    (implicit generic: Generic.Aux[P, L], fp: FnToProduct.Aux[F, L => R]): R = {
    val hlist = generic to p
    f.toProduct(hlist)
  }

  //http://typelevel.org/cats/typeclasses/applicative.html
  def main(args: Array[String]): Unit = {
    implicit val aa = new ExistedId
    implicit val ab = new UniqueName
    implicit val ac = new SuitableRoles

    val success = Right(1)

    val out =
      for {
        a <- Precondition[ExistedId, Option]
          .against(None, Set(103l, 4l, 78l, 32l, 8l, 1l))
          .fold(Left(_), { _ => success })

        b <- Precondition[SuitableRoles, cats.Id]
          .against(Set(1, 7), Set(1, 3, 4, 5, 6, 7, 8))
          .fold(Left(_), { _ => success })

        c <- Precondition[UniqueName, cats.Id]
          .against("bkl", Set("b", "c", "d", "e"))
          .fold(Left(_), { _ => success })

        d <- Right(78)

        result <- product(a, b, c, d) { (a: Int, b: Int, c: Int, e: Int) =>
          Right(a + b + c + e)
        }
      } yield result
    println(out)
  }
}