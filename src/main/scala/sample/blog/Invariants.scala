package sample.blog

import cats.Applicative
import cats.instances.option._

import scala.reflect.ClassTag

//runMain sample.blog.Invariants
object Invariants {

  implicit val setApplicative: Applicative[Set] = new Applicative[Set] {
    override def pure[A](x: A): Set[A] = Set(x)

    override def ap[A, B](ff: Set[(A) => B])(fa: Set[A]): Set[B] =
      for {a <- fa; f <- ff} yield f(a)
  }

  trait Check[In, State] {
    def name: String
    def apply(in: In, state: State): Boolean
    def errorMessage(in: In, state: State): String
    def emptyInputMessage = s"Empty input while checking $name"
  }

  sealed trait Precondition[F[_]] {
    def isPreserved[T, State](fa: F[T], on: State, ignoreNull: Boolean = false)
      (implicit P: Check[T, State], C: Catamorphism[F]): Either[String, F[Unit]]
  }

  class ExistedId extends Check[Long, Set[Long]] {
    override val name = "Precondition: KnownId"
    override def apply(in: Long, on: Set[Long]): Boolean = on.contains(in)
    override def errorMessage(in: Long, on: Set[Long]) = s"$in doesn't exists"
  }

  class UniqueName extends Check[String, Set[String]] {
    override val name = "Precondition: UniqueName"
    override def apply(name: String, state: Set[String]): Boolean = !state.contains(name)
    override def errorMessage(name: String, state: Set[String]) = s"$name is not unique"
  }

  class SuitableRoles extends Check[Set[Int], Set[Int]] {
    override val name = "Precondition: HasRoles"
    override def apply(ids: Set[Int], state: Set[Int]) = ids.filter(id => !state.contains(id)).isEmpty
    override def errorMessage(ids: Set[Int], state: Set[Int]) = s"User with roles [${ids.mkString(",")}] is not allowed to do this operation"
  }

  implicit val aa = new ExistedId()
  implicit val ab = new UniqueName()
  implicit val ac = new SuitableRoles()

  trait Catamorphism[F[_]] {
    def cata[A, B](opt: F[A])(ifNull: ⇒ Either[String, F[Unit]], ifNotNull: A ⇒ Either[String, F[Unit]]): Either[String, F[Unit]]
  }

  implicit object OptionalCatamorphism extends Catamorphism[Option] {
    override def cata[A, B](opt: Option[A])(
      ifNull: ⇒ Either[String, Option[Unit]],
      ifNotNull: (A) ⇒ Either[String, Option[Unit]]
    ): Either[String, Option[Unit]] = opt.fold(ifNull)(ifNotNull(_))
  }

  implicit object IdCatamorphism extends Catamorphism[cats.Id] {
    override def cata[A, B](opt: cats.Id[A])(
      ifNull: ⇒ Either[String, cats.Id[Unit]],
      ifNotNull: (A) ⇒ Either[String, cats.Id[Unit]]
    ): Either[String, cats.Id[Unit]] = if (opt == null) ifNull else ifNotNull(opt)
  }

  object Precondition {
    def apply[T <: Check[_, _] : ClassTag, F[_] : cats.Applicative] = {
      new Precondition[F] {
        val A: cats.Applicative[F] = implicitly[cats.Applicative[F]]
        val success: F[Unit] = A.pure(())

        override def isPreserved[T, State](in: F[T], state: State, ignoreIfAbsent: Boolean = false)
          (implicit C: Check[T, State], Cata: Catamorphism[F]): Either[String, F[Unit]] = {
          //println(P.name)
          if (ignoreIfAbsent)
            Cata.cata(in)(Right(success), { input =>
              if (C(input, state)) Right(success) else Left(C.errorMessage(input, state))
            })
          else
            Cata.cata(in)(Left(C.emptyInputMessage), { input =>
              if (C(input, state)) Right(success) else Left(C.errorMessage(input, state))
            })
        }
      }
    }
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

  * Generic.Aux[P, L]; this is the built-in “predicate” that Shapeless provides to encode the relation between a product type P and an HList L.
  * It holds when it is possible to derive a Generic[P] instance that converts P into L

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
    val success = Right(1)

    val r =
      for {
        a <- Precondition[ExistedId, Option]
          .isPreserved(None, Set(103l, 4l, 78l, 32l, 8l, 1l), true)
          .fold(Left(_), { _ => success })

        b <- Precondition[SuitableRoles, cats.Id]
          .isPreserved(Set(1, 7), Set(1, 3, 4, 5, 6, 7, 8))
          .fold(Left(_), { _ => success })

        c <- Precondition[UniqueName, cats.Id]
          .isPreserved("bkl", Set("b", "c", "d", "e"))
          .fold(Left(_), { _ => success })

        d <- Right(78)

        e <- product(a,b,c,d) { (a: Int, b: Int, c: Int, e: Int) =>
          Right(a+b+c+e)
        }
      } yield e

    println(r)
  }
}